/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Joey Gibson <joey@joeygibson.com>
 * Copyright (C) 2004-2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2005 David Corbin <dcorbin@users.sf.net>
 * Copyright (C) 2006 Michael Studman <codehaus@michaelstudman.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby;

import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.JumpException.FlowControlException;
import org.jruby.exceptions.RaiseException;
import org.jruby.java.proxies.ConcreteJavaProxy;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.backtrace.BacktraceData;
import org.jruby.runtime.backtrace.RubyStackTraceElement;
import org.jruby.runtime.backtrace.TraceType;
import org.jruby.runtime.builtin.IRubyObject;

import java.io.PrintStream;

import static org.jruby.runtime.Visibility.PRIVATE;

/**
 * An abstract representation of a Ruby exception. All Ruby exception types descend from here.
 *
 * Logic here handles aggregating a cause, connecting up a Java throwable, managing the backtrace, and other Ruby
 * behavior surrounding exceptions.
 */
public abstract class AbstractRubyException extends RubyObject {

    protected AbstractRubyException(Ruby runtime, RubyClass rubyClass) {
        this(runtime, rubyClass, null);
    }

    public AbstractRubyException(Ruby runtime, RubyClass rubyClass, String message) {
        super(runtime, rubyClass);

        this.setMessage(message == null ? runtime.getNil() : runtime.newString(message));
        this.cause = RubyBasicObject.UNDEF;
    }

    @JRubyMethod(optional = 2, visibility = PRIVATE)
    public IRubyObject initialize(IRubyObject[] args, Block block) {
        if ( args.length == 1 ) setMessage(args[0]);
        // cause filled in at RubyKernel#raise ... Exception.new does not fill-in cause!
        return this;
    }

    @JRubyMethod
    public IRubyObject backtrace() {
        return getBacktrace();
    }

    @JRubyMethod(required = 1)
    public IRubyObject set_backtrace(IRubyObject obj) {
        setBacktrace(obj);
        return backtrace();
    }

    private void setBacktrace(IRubyObject obj) {
        if (obj.isNil()) {
            backtrace = null;
        } else if (isArrayOfStrings(obj)) {
            backtrace = obj;
        } else if (obj instanceof RubyString) {
            backtrace = RubyArray.newArray(getRuntime(), obj);
        } else {
            throw getRuntime().newTypeError("backtrace must be Array of String");
        }
    }

    @JRubyMethod(omit = true)
    public IRubyObject backtrace_locations(ThreadContext context) {
        Ruby runtime = context.runtime;
        RubyStackTraceElement[] elements = backtraceData.getBacktrace(runtime);

        return RubyThread.Location.newLocationArray(runtime, elements);
    }

    @JRubyMethod(name = "exception", optional = 1, rest = true, meta = true)
    public static IRubyObject exception(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        return ((RubyClass) recv).newInstance(context, args, block);
    }

    @JRubyMethod(optional = 1)
    public AbstractRubyException exception(IRubyObject[] args) {
        switch (args.length) {
            case 0 :
                return this;
            case 1 :
                if (args[0] == this) return this;
                AbstractRubyException ret = (AbstractRubyException) rbClone();
                ret.initialize(args, Block.NULL_BLOCK); // This looks wrong, but it's the way MRI does it.
                return ret;
            default :
                throw getRuntime().newArgumentError("Wrong argument count");
        }
    }

    @JRubyMethod(name = "to_s")
    public IRubyObject to_s(ThreadContext context) {
        final IRubyObject msg = getMessage();
        if ( ! msg.isNil() ) return msg.asString();
        return context.runtime.newString(getMetaClass().getRealClass().getName());
    }

    @Deprecated
    public IRubyObject to_s19(ThreadContext context) { return to_s(context); }

    @JRubyMethod(name = "message")
    public IRubyObject message(ThreadContext context) {
        return callMethod(context, "to_s");
    }

    @JRubyMethod(name = "full_message")
    public abstract IRubyObject full_message(ThreadContext context);

    /** inspects an object and return a kind of debug information
     *
     *@return A RubyString containing the debug information.
     */
    @JRubyMethod(name = "inspect")
    public RubyString inspect(ThreadContext context) {
        // rb_class_name skips intermediate classes (JRUBY-6786)
        String rubyClass = getMetaClass().getRealClass().getName();
        RubyString exception = RubyString.objAsString(context, this);

        if (exception.isEmpty()) return context.runtime.newString(rubyClass);

        return RubyString.newString(context.runtime,
                new StringBuilder(2 + rubyClass.length() + 2 + exception.size() + 1).
                    append("#<").append(rubyClass).append(": ").append(exception.getByteList()).append('>')
        );
    }

    @Override
    @JRubyMethod(name = "==")
    public RubyBoolean op_equal(ThreadContext context, IRubyObject other) {
        if (this == other) return context.runtime.getTrue();

        boolean equal = context.runtime.getException().isInstance(other) &&
                getMetaClass().getRealClass() == other.getMetaClass().getRealClass() &&
                callMethod(context, "message").equals(other.callMethod(context, "message")) &&
                callMethod(context, "backtrace").equals(other.callMethod(context, "backtrace"));
        return context.runtime.newBoolean(equal);
    }

    @JRubyMethod(name = "===", meta = true)
    public static IRubyObject op_eqq(ThreadContext context, IRubyObject recv, IRubyObject other) {
        Ruby runtime = context.runtime;
        // special case non-FlowControlException Java exceptions so they'll be caught by rescue Exception
        if (other instanceof ConcreteJavaProxy &&
                (recv == runtime.getException() || recv == runtime.getStandardError())) {

            Object object = ((ConcreteJavaProxy)other).getObject();
            if (object instanceof Throwable && !(object instanceof FlowControlException)) {
                if (recv == runtime.getException() || object instanceof Exception) {
                    return context.runtime.getTrue();
                }
            }
        }
        // fall back on default logic
        return ((RubyClass)recv).op_eqq(context, other);
    }

    @JRubyMethod(name = "cause")
    public IRubyObject cause(ThreadContext context) {
        assert cause != null;
        return cause == RubyBasicObject.UNDEF ? context.nil : cause;
    }

    /**
     * Coerce this Ruby exception to the requested Java type, if possible.
     *
     * If the requested type is a supertype of RaiseException, the attached throwable will be returned.
     *
     * Otherwise, it will fall back on RubyBasicObject toJava logic.
     *
     * @param target the target type to which this object should be converted
     * @return the converted result
     */
    @Override
    public Object toJava(Class target) {
        if (target.isAssignableFrom(RaiseException.class)) {
            return target.cast(throwable);
        }
        return super.toJava(target);
    }

    /**
     * Construct an appropriate throwable for this Ruby exception.
     *
     * All such throwables must descend from the {@link org.jruby.exceptions.Exception} hierarchy and will be cached
     * after construction.
     *
     * @param message the message to pass to the Java constructor for the throwable
     * @return a throwable RaiseException attached to this Ruby exception
     */
    protected abstract RaiseException constructThrowable(String message);

    /**
     * Get a throwable suitable for throwing in Java.
     *
     * The throwable here will be constructed lazily by calling constructThrowable and then cached for future calls.
     *
     * All throwables returned by Ruby exception objects will descend from RaiseException and follow the Throwable
     * hierarchy below {@link org.jruby.exceptions.Exception}
     * @return
     */
    public RaiseException toThrowable() {
        if (throwable == null) {
            return throwable = constructThrowable(getMessageAsJavaString());
        }
        return throwable;
    }

    public void setCause(IRubyObject cause) {
        this.cause = cause;
    }

    // NOTE: can not have IRubyObject as NativeException has getCause() returning Throwable
    public Object getCause() {
        return cause == RubyBasicObject.UNDEF ? null : cause;
    }

    public void setBacktraceData(BacktraceData backtraceData) {
        this.backtraceData = backtraceData;
    }

    public BacktraceData getBacktraceData() {
        return backtraceData;
    }

    public RubyStackTraceElement[] getBacktraceElements() {
        if (backtraceData == null) {
            return RubyStackTraceElement.EMPTY_ARRAY;
        }
        return backtraceData.getBacktrace(getRuntime());
    }

    public void prepareBacktrace(ThreadContext context) {
        // if it's null, build a backtrace
        if (backtraceData == null) {
            backtraceData = context.runtime.getInstanceConfig().getTraceType().getBacktrace(context);
        }
    }

    /**
     * Prepare an "integrated" backtrace that includes the normal Ruby trace plus non-filtered Java frames. Used by
     * Java integration to show the Java frames for a JI-called method.
     *
     * @param context
     * @param javaTrace
     */
    public void prepareIntegratedBacktrace(ThreadContext context, StackTraceElement[] javaTrace) {
        // if it's null, build a backtrace
        if (backtraceData == null) {
            backtraceData = context.runtime.getInstanceConfig().getTraceType().getIntegratedBacktrace(context, javaTrace);
        }
    }

    public void forceBacktrace(IRubyObject backtrace) {
        backtraceData = (backtrace != null && backtrace.isNil()) ? null : BacktraceData.EMPTY;
        setBacktrace(backtrace);
    }

    public IRubyObject getBacktrace() {
        if (backtrace == null) {
            initBacktrace();
        }
        return backtrace;
    }

    public void initBacktrace() {
        Ruby runtime = getRuntime();
        if (backtraceData == null) {
            backtrace = runtime.getNil();
        } else {
            backtrace = TraceType.generateMRIBacktrace(runtime, backtraceData.getBacktrace(runtime));
        }
    }

    @Override
    public void copySpecialInstanceVariables(IRubyObject clone) {
        AbstractRubyException exception = (AbstractRubyException)clone;
        exception.backtraceData = backtraceData;
        exception.backtrace = backtrace;
        exception.message = message;
    }

    /**
     * Print the Ruby exception's backtrace to the given PrintStream.
     *
     * @param errorStream the PrintStream to which backtrace should be printed
     */
    public void printBacktrace(PrintStream errorStream) {
        printBacktrace(errorStream, 0);
    }

    /**
     * Print the Ruby exception's backtrace to the given PrintStream. This
     * version accepts a number of lines to skip and is primarily used
     * internally for exception printing where the first line is treated specially.
     *
     * @param errorStream the PrintStream to which backtrace should be printed
     */
    public void printBacktrace(PrintStream errorStream, int skip) {
        IRubyObject trace = callMethod(getRuntime().getCurrentContext(), "backtrace");
        if ( trace.isNil() ) return;
        if ( trace instanceof RubyArray ) {
            IRubyObject[] elements = ((RubyArray) trace).toJavaArrayMaybeUnsafe();
            for (int i = skip; i < elements.length; i++) {
                IRubyObject stackTraceLine = elements[i];
                if (stackTraceLine instanceof RubyString) {
                    errorStream.println("\tfrom " + stackTraceLine);
                }
                else {
                    errorStream.println("\t" + stackTraceLine);
                }
            }
        }
    }

    private boolean isArrayOfStrings(IRubyObject backtrace) {
        if (!(backtrace instanceof RubyArray)) return false;

        final RubyArray rTrace = ((RubyArray) backtrace);

        for (int i = 0 ; i < rTrace.getLength() ; i++) {
            if (!(rTrace.eltInternal(i) instanceof RubyString)) return false;
        }

        return true;
    }

    /**
     * @return error message if provided or nil
     */
    public IRubyObject getMessage() {
        return message == null ? getRuntime().getNil() : message;
    }

    /**
     * Set the message for this NameError.
     * @param message the message
     */
    public void setMessage(IRubyObject message) {
        this.message = message;
    }

    public String getMessageAsJavaString() {
        final IRubyObject msg = getMessage();
        return msg.isNil() ? null : msg.toString();
    }

    protected BacktraceData backtraceData;
    private IRubyObject backtrace;
    IRubyObject message;
    IRubyObject cause;
    private RaiseException throwable;

    public static final int TRACE_HEAD = 8;
    public static final int TRACE_TAIL = 4;
    public static final int TRACE_MAX = TRACE_HEAD + TRACE_TAIL + 6;
}