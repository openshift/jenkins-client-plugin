package com.openshift.jenkins.plugins.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

import hudson.Platform;
import jenkins.security.MasterToSlaveCallable;

public class FindOC extends MasterToSlaveCallable<ArrayList<String>, Throwable> {

    private static final long serialVersionUID = 1L;
    private String path;
    public String getPath() {
        return path;
    }
    public void setPath(String path) {
        this.path = path;
    }
    public FindOC() {
        
    }
    @Override
    public ArrayList<String> call() throws Throwable {
        String ocFileName = "oc";
        String pathSepChar = ":";
        if (Platform.current() == Platform.WINDOWS) {
            ocFileName = "oc.exe";
            pathSepChar = ";";
        }
        ArrayList<Path> paths = new ArrayList<Path>();
        String[] dirs = path.split(pathSepChar);
        for (String dir : dirs) {
            paths.add(Paths.get(dir));
        }
        
        final String oc = ocFileName;
        final ArrayList<String> ocPath = new ArrayList<String>();
        for (Path path : paths) {
            Files.walkFileTree(path, new java.nio.file.FileVisitor<Path>() {

                @Override
                public FileVisitResult postVisitDirectory(Path arg0, IOException arg1) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path arg0, BasicFileAttributes arg1) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attr) throws IOException {
                    if (!attr.isRegularFile())
                        return FileVisitResult.CONTINUE;
                    File f = path.toFile();
                    if (!f.canRead() && !f.canExecute())
                        return FileVisitResult.CONTINUE;
                    if (path.getFileName().toString().equals(oc))
                        ocPath.add(path.toString());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path arg0, IOException arg1) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
                
            });            
        }
        return ocPath;
    }

}
