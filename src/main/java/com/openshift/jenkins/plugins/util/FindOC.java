/*
 * Copyright © 2016 Red Hat, Inc. (https://www.redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
