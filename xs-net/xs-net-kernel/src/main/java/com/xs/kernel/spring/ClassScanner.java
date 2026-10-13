package com.xs.kernel.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类扫描工具
 * 提供类似 Spring @ComponentScan 的功能，通过类加载器扫描指定包下的类
 * 支持自动扫描所有包（当未指定包路径时）
 */
public class ClassScanner {

    private static final Logger log = LoggerFactory.getLogger(ClassScanner.class);

    /**
     * 扫描指定包下符合条件的类
     * 如果 basePackages 为空或长度为 0，则扫描所有包
     *
     * @param basePackages 基础包路径数组
     * @param filter       类过滤器
     * @return 扫描到的类集合
     */
    public static Set<Class<?>> scanClasses(String[] basePackages, Predicate<Class<?>> filter) {
        Set<Class<?>> result = new HashSet<>();

        // 如果没有指定包路径，则扫描所有包
        if (basePackages == null || basePackages.length == 0) {
            log.info("未指定扫描包路径，将扫描所有包");
            scanAllPackages(result, filter);
        } else {
            // 扫描指定的包
            for (String basePackage : basePackages) {
                try {
                    List<Class<?>> classes = scanPackage(basePackage);
                    for (Class<?> clazz : classes) {
                        if (filter == null || filter.test(clazz)) {
                            result.add(clazz);
                        }
                    }
                } catch (Exception e) {
                    log.error("扫描包 {} 时发生错误", basePackage, e);
                }
            }
        }

        return result;
    }

    /**
     * 扫描所有包
     */
    private static void scanAllPackages(Set<Class<?>> result, Predicate<Class<?>> filter) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources("");

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();

                if ("file".equals(protocol)) {
                    String filePath = resource.getFile();
                    scanDirectoryRecursively(new File(filePath), "", result, filter);
                } else if ("jar".equals(protocol)) {
                    scanJarForAll(resource, result, filter);
                }
            }
        } catch (Exception e) {
            log.error("扫描所有包时发生错误", e);
        }
    }

    /**
     * 递归扫描目录下的所有类
     */
    private static void scanDirectoryRecursively(File directory, String packageName, Set<Class<?>> result, Predicate<Class<?>> filter) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                String subPackage = packageName.isEmpty() ? file.getName() : packageName + "." + file.getName();
                scanDirectoryRecursively(file, subPackage, result, filter);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName.isEmpty()
                    ? file.getName().substring(0, file.getName().length() - 6)
                    : packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                try {
                    Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                    if (filter == null || filter.test(clazz)) {
                        result.add(clazz);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    log.debug("无法加载类: {}", className);
                }
            }
        }
    }

    /**
     * 扫描 JAR 包中的所有类
     */
    private static void scanJarForAll(URL resource, Set<Class<?>> result, Predicate<Class<?>> filter) {
        try {
            java.net.JarURLConnection jarConnection = (java.net.JarURLConnection) resource.openConnection();
            JarFile jarFile = jarConnection.getJarFile();
            
            // 检查是否为多版本 JAR，如果是且版本高于当前 JVM，则跳过
            String manifestVersion = jarFile.getManifest() != null 
                ? jarFile.getManifest().getMainAttributes().getValue("Multi-Release") 
                : null;
            
            if ("true".equalsIgnoreCase(manifestVersion)) {
                log.debug("跳过扫描多版本 JAR: {}", jarFile.getName());
                return;
            }
            
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // 跳过 META-INF、JDK 内部类等
                if (entryName.startsWith("META-INF/") || 
                    entryName.startsWith("jdk/") ||
                    entryName.contains("/asmjdkbridge/") ||
                    entryName.contains("$")) {
                    continue;
                }

                if (entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    
                    // 跳过 JDK 和第三方库的内部类
                    if (className.startsWith("jdk.") || 
                        className.startsWith("sun.") ||
                        className.contains("net.bytebuddy.jar.asm")) {
                        continue;
                    }
                    
                    try {
                        Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                        if (filter == null || filter.test(clazz)) {
                            result.add(clazz);
                        }
                    } catch (ClassNotFoundException | LinkageError e) {
                        log.debug("无法加载类：{}", className);
                    } catch (Throwable t) {
                        log.warn("加载类 {} 时发生非致命异常，已跳过：{}", className, t.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.error("扫描 JAR 包时发生错误", e);
        }
    }

    /**
     * 扫描指定包下的所有类
     *
     * @param packageName 包名
     * @return 类列表
     */
    private static List<Class<?>> scanPackage(String packageName) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = classLoader.getResources(packagePath);

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            String protocol = resource.getProtocol();

            if ("file".equals(protocol)) {
                String filePath = resource.getFile();
                scanDirectory(new File(filePath), packageName, classes);
            } else if ("jar".equals(protocol)) {
                scanJar(resource, packagePath, classes);
            }
        }

        return classes;
    }

    /**
     * 扫描文件目录
     */
    private static void scanDirectory(File directory, String packageName, List<Class<?>> classes) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                try {
                    Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                    classes.add(clazz);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    log.debug("无法加载类: {}", className);
                }
            }
        }
    }

    /**
     * 扫描 JAR 包
     */
    private static void scanJar(URL resource, String packagePath, List<Class<?>> classes) {
        try {
            java.net.JarURLConnection jarConnection = (java.net.JarURLConnection) resource.openConnection();
            JarFile jarFile = jarConnection.getJarFile();
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(packagePath) && entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    try {
                        Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                        classes.add(clazz);
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        log.debug("无法加载类: {}", className);
                    }
                }
            }
        } catch (Exception e) {
            log.error("扫描 JAR 包时发生错误", e);
        }
    }
}
