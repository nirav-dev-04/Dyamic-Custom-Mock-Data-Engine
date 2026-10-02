package com.example.mockengine.controller;

import net.datafaker.Faker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
public class AutoMockController {

    private final Faker faker = new Faker();
    
    // Performance Cache: ConcurrentHashMap mapping category.method -> (provider, method)
    private static final Map<String, MethodTarget> METHOD_CACHE = new ConcurrentHashMap<>();

    private record MethodTarget(Object provider, Method method) {}

    public AutoMockController() {
        initReflectionCache();
    }

    /**
     * Scans Faker provider objects and caches zero-argument invokable methods.
     * Speeds up real-time dynamic data retrieval by avoiding redundant runtime reflection lookups.
     */
    private void initReflectionCache() {
        Method[] fakerMethods = Faker.class.getMethods();
        for (Method providerMethod : fakerMethods) {
            if (providerMethod.getParameterCount() == 0 &&
                !providerMethod.getDeclaringClass().equals(Object.class) &&
                !providerMethod.getReturnType().equals(Void.TYPE) &&
                !providerMethod.getReturnType().equals(Faker.class)) {

                try {
                    Object providerInstance = providerMethod.invoke(faker);
                    if (providerInstance == null) continue;

                    String categoryName = providerMethod.getName();

                    Method[] targetMethods = providerInstance.getClass().getMethods();
                    for (Method targetMethod : targetMethods) {
                        if (targetMethod.getParameterCount() == 0 &&
                            !targetMethod.getDeclaringClass().equals(Object.class) &&
                            !targetMethod.getName().startsWith("wait") &&
                            !targetMethod.getName().startsWith("notify") &&
                            !targetMethod.getName().equals("equals") &&
                            !targetMethod.getName().equals("hashCode") &&
                            !targetMethod.getName().equals("toString")) {

                            String lowerKey = (categoryName + "." + targetMethod.getName()).toLowerCase();
                            String exactKey = categoryName + "." + targetMethod.getName();
                            
                            MethodTarget target = new MethodTarget(providerInstance, targetMethod);
                            METHOD_CACHE.putIfAbsent(lowerKey, target);
                            METHOD_CACHE.putIfAbsent(exactKey, target);
                        }
                    }
                } catch (Exception ignored) {
                    // Suppress provider invocation errors during startup scan
                }
            }
        }
    }

    /**
     * Exposes endpoint GET /api/catalog
     * Returns mapped categories and sub-topics directly from the backend library.
     */
    @GetMapping("/api/catalog")
    public ResponseEntity<Map<String, List<String>>> getCatalog() {
        Map<String, List<String>> catalogMap = new TreeMap<>();
        Set<String> ignoredSubtopics = Set.of(
            "getfaker", "getFaker", "tostring", "equals", "hashcode", 
            "wait", "notify", "clone", "finalize", "class", "getclass",
            "notifyall", "notifyAll", "toString", "hashCode", "getClass"
        );

        for (String key : METHOD_CACHE.keySet()) {
            if (key.contains(".")) {
                String[] parts = key.split("\\.", 2);
                String category = parts[0].toLowerCase();
                String subTopic = parts[1];

                if (ignoredSubtopics.contains(subTopic) || ignoredSubtopics.contains(subTopic.toLowerCase())) {
                    continue;
                }
                
                catalogMap.computeIfAbsent(category, k -> new ArrayList<>()).add(subTopic);
            }
        }
        // Deduplicate and sort sub-topics
        Map<String, List<String>> sortedCatalog = new TreeMap<>();
        catalogMap.forEach((cat, subList) -> {
            List<String> distinct = subList.stream()
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            if (!distinct.isEmpty()) {
                sortedCatalog.put(cat, distinct);
            }
        });
        return ResponseEntity.ok(sortedCatalog);
    }

    /**
     * Exposes endpoint GET /api/generate?fields=...&count=...
     * Returns dynamic JSON dataset.
     */
    @GetMapping("/api/generate")
    public ResponseEntity<List<Map<String, Object>>> generateMockData(
            @RequestParam(name = "fields", defaultValue = "name.fullName,commerce.price") String fields,
            @RequestParam(name = "count", defaultValue = "10") int count) {

        int clampedCount = Math.min(Math.max(1, count), 1000);
        String[] requestedFields = fields.split(",");

        List<Map<String, Object>> dataset = new ArrayList<>();

        for (int i = 0; i < clampedCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String fieldRaw : requestedFields) {
                String fieldToken = fieldRaw.trim();
                if (fieldToken.isEmpty()) continue;

                MethodTarget target = METHOD_CACHE.get(fieldToken);
                if (target == null) {
                    target = METHOD_CACHE.get(fieldToken.toLowerCase());
                }
                if (target == null && fieldToken.toLowerCase().startsWith("car.")) {
                    String vehicleKey = fieldToken.toLowerCase().replace("car.", "vehicle.");
                    target = METHOD_CACHE.get(vehicleKey);
                }
                if (target == null && fieldToken.toLowerCase().equals("car.model")) {
                    target = METHOD_CACHE.get("vehicle.model");
                }

                if (target != null) {
                    try {
                        Object value = target.method().invoke(target.provider());
                        row.put(fieldToken, value != null ? value.toString() : "");
                    } catch (Exception e) {
                        row.put(fieldToken, "Field Not Supported");
                    }
                } else {
                    row.put(fieldToken, "Field Not Supported");
                }
            }
            dataset.add(row);
        }

        return ResponseEntity.ok(dataset);
    }
}
