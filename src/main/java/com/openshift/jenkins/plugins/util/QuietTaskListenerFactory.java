package com.openshift.jenkins.plugins.util;

import hudson.model.TaskListener;

import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class QuietTaskListenerFactory {

    public static QuietTasklistener build(TaskListener listener) {
        return (QuietTasklistener) Proxy.newProxyInstance(
                QuietTaskListenerFactory.class.getClassLoader(),
                new Class[] { QuietTasklistener.class },
                new QuietTaskListenerIH(listener));
    }

    public static class QuietTaskListenerIH implements InvocationHandler, Externalizable {

        private TaskListener underlying;
        private ByteArrayOutputStream logContent = new ByteArrayOutputStream();
        private PrintStream out;
        
        public QuietTaskListenerIH() {
        	
        }

        protected QuietTaskListenerIH(TaskListener underlying) {
            this.underlying = underlying;
            PrintStream tmp = null;
            try {
                tmp = new PrintStream(logContent, false, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("UTF8 not supported", e);
            }
            out = tmp;
        }

        /**
         * The goal is to intercept calls to the TaskListener's getLogger method
         * to return our own PrintStream. We implement QuietTasklistener, so we
         * must also intercept/satisfy __getLogOutput.
         */
        @Override
        public Object invoke(Object o, Method method, Object[] objects)
                throws Throwable {
            if ("getLogger".equals(method.getName())) {
                return out;
            }
            if ("__getLogOutput".equals(method.getName())) {
                out.flush();
                return logContent.toString("UTF-8");
            }
            // If neither signature matches, pass it on to the underlying
            // TaskListener
            return method.invoke(o, objects);
        }

        @Override
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            underlying = (TaskListener)in.readObject();
            PrintStream tmp = null;
            try {
                tmp = new PrintStream(logContent, false, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("UTF8 not supported", e);
            }
            out = tmp;

        }

        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(underlying);
			
        }

    }

    public interface QuietTasklistener extends TaskListener {
        public String __getLogOutput();
    }

}
