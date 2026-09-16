package com.xs.net.message;

@FunctionalInterface
public interface RouteValueExtractor {
    String extract(Object message);
}
