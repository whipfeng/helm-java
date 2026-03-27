/*
 * Copyright 2024 Marc Nuri
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

package com.marcnuri.helm.jni;

import com.sun.jna.Native;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.ServiceLoader;

import static com.marcnuri.helm.jni.RemoteJarLoader.remoteJar;

/**
 * @author Marc Nuri
 */
public interface NativeLibrary {

  static NativeLibrary getInstance() {
    NativeLibrary nativeLibrary;
    ClassLoader remoteJar;
    if (
      // Load from ClassPath (should work on Maven)
      (nativeLibrary = serviceProviderLibrary(null)) != null ||
        // Load from remote JAR (should work on Gradle if not air-gapped)
        ((remoteJar = remoteJar()) != null && (nativeLibrary = serviceProviderLibrary(remoteJar)) != null)
    ) {
      return nativeLibrary;
    }
    throw new IllegalStateException("No NativeLibrary implementation found, please add one of the supported dependencies to your project");
  }

    static NativeLibrary serviceProviderLibrary(ClassLoader classLoader) {
        final ClassLoader effectiveCl = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        final String os = detectOs();
        final String arch = detectArch();
        if (os == null || arch == null) {
            return null;
        }
        for (NativeLibrary lib : ServiceLoader.load(NativeLibrary.class, effectiveCl)) {
            final String bin = lib.getBinaryName();
            if (bin != null) {
                final String lower = bin.toLowerCase();
                if (lower.contains(os) && lower.contains(arch)) {
                    return lib;
                }
            }
        }
        return null;
    }

    static String detectOs() {
        final String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("mac")) {
            return "darwin";
        } else if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return "linux";
        }
        return null;
    }

    static String detectArch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase();
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            return "amd64";
        } else if ("arm64".equals(arch) || "aarch64".equals(arch)) {
            return "arm64";
        }
        return null;
    }


  String getBinaryName();

  default HelmLib load() {
    final Path temp = createTempDirectory();
    final Path tempBinary = temp.resolve(getBinaryName());
    tempBinary.toFile().deleteOnExit();
    try (final InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/" + getBinaryName()))) {
      Files.copy(stream, tempBinary, StandardCopyOption.REPLACE_EXISTING);
      final HelmLib helmLib = Native.load(tempBinary.toAbsolutePath().toString(), HelmLib.class);
      // Cleanup any resources that might have been left behind
      Runtime.getRuntime().addShutdownHook(new Thread(helmLib::RepoServerStopAll));
      return helmLib;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load native library " + getBinaryName(), exception);
    }
  }

  default Path createTempDirectory() {
    try {
      final Path temp = Files.createTempDirectory("helm-java");
      temp.toFile().deleteOnExit();
      return temp;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to create temporary directory", exception);
    }
  }

}
