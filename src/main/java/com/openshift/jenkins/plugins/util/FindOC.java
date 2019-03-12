package com.openshift.jenkins.plugins.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import hudson.Platform;
import jenkins.security.MasterToSlaveCallable;

public class FindOC extends MasterToSlaveCallable<List<String>, Throwable> {

    private static final long serialVersionUID = 1L;
    private String path;

    public FindOC(String path) {
        this.path = path;
    }

    @Override
    public List<String> call() {
        final String ocFileName = Platform.current() == Platform.WINDOWS ? "oc.exe" : "oc";
        String[] dirs = path.split(File.pathSeparator);
        return Arrays.stream(dirs)
                .map(dir -> new File(dir, ocFileName))
                .filter(file -> file.isFile() && file.canExecute())
                .map(file -> file.getAbsolutePath())
                .collect(Collectors.toList());
    }
}
