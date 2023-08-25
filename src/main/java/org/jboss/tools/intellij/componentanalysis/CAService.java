/*******************************************************************************
 * Copyright (c) 2023 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/

package org.jboss.tools.intellij.componentanalysis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.redhat.exhort.api.AnalysisReport;
import com.redhat.exhort.api.DependencyReport;
import org.jboss.tools.intellij.exhort.ApiService;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class CAService {

    public static final DependencyReport NO_VUL_DEP = new DependencyReport();

    public static CAService getInstance() {
        return ServiceManager.getService(CAService.class);
    }

    private final Cache<String, Cache<Dependency, DependencyReport>> vulnerabilityCache = Caffeine.newBuilder()
            .expireAfterWrite(50, TimeUnit.MINUTES)
            .maximumSize(100)
            .build();

    private static Cache<Dependency, DependencyReport> getCache(String filePath) {
        return getInstance().vulnerabilityCache.get(filePath, p ->
                Caffeine.newBuilder()
                        .expireAfterWrite(1, TimeUnit.MINUTES)
                        .build());
    }

    private static void deleteCache(String filePath) {
        getInstance().vulnerabilityCache.invalidate(filePath);
    }

    public static Map<Dependency, DependencyReport> getReports(String filePath, Collection<Dependency> dependencies) {
        return getCache(filePath).getAllPresent(dependencies);
    }

    public static void deleteReports(String filePath) {
        deleteCache(filePath);
    }

    public static boolean performAnalysis(String packageManager,
                                          String fileName,
                                          String filePath,
                                          Collection<Dependency> dependencies) {
        Cache<Dependency, DependencyReport> cache = getCache(filePath);
        if (!cache.getAllPresent(dependencies).keySet().containsAll(dependencies)) {
            ApiService apiService = ServiceManager.getService(ApiService.class);
            AnalysisReport report = apiService.getComponentAnalysis(packageManager, fileName, filePath);
            if (report != null && report.getDependencies() != null) {
                // Avoid comparing the version of dependency
                Map<Dependency, Dependency> dependencyMap = Collections.unmodifiableMap(
                        dependencies
                                .parallelStream()
                                .collect(Collectors.toMap(
                                        Function.identity(),
                                        d -> new Dependency(d, false),
                                        (o1, o2) -> o1
                                ))
                );

                Map<Dependency, DependencyReport> reportMap = Collections.unmodifiableMap(
                        report.getDependencies()
                                .parallelStream()
                                .filter(r -> Objects.nonNull(r.getRef()))
                                .collect(Collectors.toMap(
                                        r -> new Dependency(r.getRef().purl(), false),
                                        Function.identity(),
                                        (o1, o2) -> o1
                                ))
                );

                Map<Dependency, DependencyReport> resultMap = dependencyMap.entrySet()
                        .parallelStream()
                        .map(e -> {
                            DependencyReport dp = reportMap.get(e.getValue());
                            return new AbstractMap.SimpleEntry<>(e.getKey(), Objects.requireNonNullElse(dp, NO_VUL_DEP));
                        })
                        .collect(Collectors.toMap(
                                AbstractMap.SimpleEntry::getKey,
                                AbstractMap.SimpleEntry::getValue,
                                (o1, o2) -> o1
                        ));

                if (resultMap.keySet().containsAll(dependencies)) {
                    cache.putAll(resultMap);
                    return true;
                }
            }
        }
        return false;
    }
}
