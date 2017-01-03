package com.openshift.jenkins.plugins.util;

import hudson.model.TaskListener;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class QuietTaskListenerFactory {

    public static QuietTasklistener build(TaskListener listener){
        ByteArrayOutputStream loggerOutput = new ByteArrayOutputStream();
        return (QuietTasklistener) Proxy.newProxyInstance(
                QuietTaskListenerFactory.class.getClassLoader(),
                new Class[]{QuietTasklistener.class},
                new QuietTaskListenerIH(listener));
    }

    protected static class QuietTaskListenerIH implements InvocationHandler {

        private final TaskListener underlying;
        private final ByteArrayOutputStream logContent = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(logContent);


        protected QuietTaskListenerIH( TaskListener underlying ) {
            this.underlying = underlying;
        }

        /**
         * The goal is to intercept calls to the TaskListener's getLogger
         * method to return our own PrintStream. We implement QuietTasklistener,
         * so we must also intercept/satisfy __getLogOutput.
         */
        @Override
        public Object invoke(Object o, Method method, Object[] objects) throws Throwable {
            if ( "getLogger".equals(method.getName())) {
                return out;
            }
            if ( "__getLogOutput".equals(method.getName())) {
                out.flush();
                return logContent.toString();
            }
            // If neither signature matches, pass it on to the underlying TaskListener
            return method.invoke(o,objects);
        }

    }

    public interface QuietTasklistener extends TaskListener {
        public String __getLogOutput();
    }

}
