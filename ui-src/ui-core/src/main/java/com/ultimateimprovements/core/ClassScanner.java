package com.ultimateimprovements.core;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ClassScanner — scans the plugin JAR and finds classes with the given annotation.
 * <p>
 * Used for auto-discovery:
 * <ul>
 *   <li>{@link SubCommandInfo} — {@code /ui} subcommands</li>
 *   <li>{@link ModuleInfo} — plugin modules</li>
 * </ul>
 * <p>
 * Requires no external libraries (scans JAR entries via {@link JarFile}).
 */
public final class ClassScanner {

    private ClassScanner() {}

    /**
     * Scans the plugin JAR file and finds all classes with the given annotation.
     *
     * @param jarFile       the plugin JAR file (from {@link Main#getPluginFile()})
     * @param annotation    the annotation class to search for
     * @param packagePrefix package filter (e.g. "com.ultimateimprovements")
     * @param <A>           the annotation type
     * @return list of classes bearing the annotation
     */
    public static <A extends Annotation> List<Class<?>> findAnnotatedClasses(
            File jarFile, Class<A> annotation, String packagePrefix) {
        return findAnnotatedClasses(jarFile, annotation, packagePrefix,
                ClassScanner.class.getClassLoader());
    }

    /**
     * Same as {@link #findAnnotatedClasses(File, Class, String)} but loads the
     * discovered classes through an explicit class loader.
     * <p>
     * Required when one addon scans a JAR whose classes are loaded by a
     * <em>different</em> {@code PluginClassLoader} (e.g. UI-Other scanning its
     * own JAR): UI-Core's loader cannot see addon classes, so the default
     * loader would silently skip every entry.
     *
     * @param loader        the class loader that can resolve the scanned classes
     * @param <A>           the annotation type
     * @return list of classes bearing the annotation
     */
    public static <A extends Annotation> List<Class<?>> findAnnotatedClasses(
            File jarFile, Class<A> annotation, String packagePrefix, ClassLoader loader) {

        ClassLoader classLoader = loader != null ? loader : ClassScanner.class.getClassLoader();
        List<Class<?>> result = new ArrayList<>();

        if (jarFile == null || !jarFile.exists()) {
            return result;
        }

        String prefix = packagePrefix.replace('.', '/');

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Only .class files in the target package
                if (!name.endsWith(".class") || !name.startsWith(prefix)) {
                    continue;
                }

                // Skip inner classes
                if (name.contains("$")) continue;

                // com/ultimateimprovements/command/subcommands/Example.class → com.ultimateimprovements.command.subcommands.Example
                String className = name.replace('/', '.')
                        .substring(0, name.length() - ".class".length());

                try {
                    Class<?> clazz = Class.forName(className, false, classLoader);

                    if (clazz.isAnnotationPresent(annotation)) {
                        // Check that it is not an abstract class and not an interface
                        if (!clazz.isInterface() && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                            result.add(clazz);
                        }
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // The class may depend on the Bukkit API — skip it
                }
            }
        } catch (Exception e) {
            // In a dev environment (IDE) the JAR may not exist — scan the classpath
            return findAnnotatedClassesClasspath(annotation, packagePrefix, classLoader);
        }

        return result;
    }

    /**
     * Fallback: scans the classpath for a dev environment without a JAR.
     */
    private static <A extends Annotation> List<Class<?>> findAnnotatedClassesClasspath(
            Class<A> annotation, String packagePrefix, ClassLoader loader) {

        List<Class<?>> result = new ArrayList<>();
        String packagePath = packagePrefix.replace('.', '/');

        ClassLoader cl = loader;
        if (!(cl instanceof URLClassLoader ucl)) return result;

        for (URL url : ucl.getURLs()) {
            File file = new File(url.getFile());
            if (file.isDirectory()) {
                scanDirectory(file, file, packagePath, annotation, result, loader);
            }
        }

        return result;
    }

    private static <A extends Annotation> void scanDirectory(
            File root, File dir, String packagePath,
            Class<A> annotation, List<Class<?>> result, ClassLoader loader) {

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(root, file, packagePath, annotation, result, loader);
            } else if (file.getName().endsWith(".class") && !file.getName().contains("$")) {
                // Compute the full class name from the path
                String relativePath = file.getAbsolutePath()
                        .substring(root.getAbsolutePath().length() + 1)
                        .replace(File.separatorChar, '.');
                relativePath = relativePath.substring(0, relativePath.length() - ".class".length());

                if (!relativePath.startsWith(packagePath.replace('/', '.'))) continue;

                try {
                    Class<?> clazz = Class.forName(relativePath, false, loader);
                    if (clazz.isAnnotationPresent(annotation)
                            && !clazz.isInterface()
                            && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                        result.add(clazz);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // skip
                }
            }
        }
    }
}
