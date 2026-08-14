package com.xs.commons.lang4.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类扫描器
 * 
 * @author  https://github.com/xunsang2022
 */
@Slf4j
public class ClassScanner {
    
    private final String packageName;
    private final Predicate<Class<?>> filter;
    
    public ClassScanner(String packageName) {
        this(packageName, clazz -> true);
    }
    
    public ClassScanner(String packageName, Predicate<Class<?>> filter) {
        this.packageName = packageName;
        this.filter = filter != null ? filter : clazz -> true;
    }
    
    /**
     * 扫描指定包下的所有类
     *
     * @return 类列表
     */
    public List<Class<?>> listScan() {
        List<Class<?>> classes = new ArrayList<>();
        
        try {
            String packagePath = packageName.replace('.', '/');
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources(packagePath);
            
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();
                
                if ("file".equals(protocol)) {
                    // 扫描文件系统中的类
                    scanFromFileSystem(resource.getFile(), packageName, classes);
                } else if ("jar".equals(protocol)) {
                    // 扫描jar包中的类
                    scanFromJar(resource, packagePath, classes);
                }
            }
        } catch (IOException e) {
            log.error("扫描包 {} 时发生错误", packageName, e);
        }
        
        return classes;
    }
    
    /**
     * 从文件系统扫描类
     */
    private void scanFromFileSystem(String packagePath, String packageName, List<Class<?>> classes) {
        File dir = new File(packagePath);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归扫描子包
                scanFromFileSystem(file.getAbsolutePath(), packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                // 加载类
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                loadClass(className, classes);
            }
        }
    }
    
    /**
     * 从jar包扫描类
     */
    private void scanFromJar(URL resource, String packagePath, List<Class<?>> classes) {
        try {
            String jarPath = resource.getPath();
            if (jarPath.startsWith("file:")) {
                jarPath = jarPath.substring(5);
            }
            
            int exclamationIndex = jarPath.indexOf('!');
            if (exclamationIndex != -1) {
                jarPath = jarPath.substring(0, exclamationIndex);
            }
            
            try (JarFile jarFile = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    
                    if (entryName.startsWith(packagePath) && entryName.endsWith(".class")) {
                        String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                        loadClass(className, classes);
                    }
                }
            }
        } catch (IOException e) {
            log.error("扫描jar包时发生错误: {}", resource, e);
        }
    }
    
    /**
     * 加载类并应用过滤器
     */
    private void loadClass(String className, List<Class<?>> classes) {
        try {
            Class<?> clazz = Class.forName(className);
            if (filter.test(clazz)) {
                classes.add(clazz);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.debug("无法加载类: {}", className);
        } catch (Exception e) {
            log.warn("加载类时发生错误: {}", className, e);
        }
    }
} 