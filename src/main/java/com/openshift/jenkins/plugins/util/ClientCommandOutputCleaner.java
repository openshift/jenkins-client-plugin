package com.openshift.jenkins.plugins.util;

public class ClientCommandOutputCleaner {
    public static String redactSensitiveData(final String output){
        return output.replaceAll("(\"data\":)\\{(.*?)\\}", "$1{ REDACTED }");
    }
}
