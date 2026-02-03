package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.code.BinaryOperatorKind;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class SpoonCodeAnalyzer {

    private final PropertyResolver propertyResolver;
    private final KafkaAnalyzer kafkaAnalyzer;
    private final CodeExtractionHelper extractionHelper;

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"
    );

    private static final Map<String, String> ANNOTATION_TO_HTTP_METHOD = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "REQUEST"
    );

    private static final Set<String> REST_TEMPLATE_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "put", "delete", "exchange"
    );

    private static final Set<String> WEBCLIENT_HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "method"
    );

    public List<CodeAnalysisResponse> analyzeCode(Path repositoryPath, String projectId, String repoUrl) {
        log.info("Starting Spoon code analysis for repository: {}", repositoryPath);

        // Load properties from application.yaml/properties
        Map<String, String> properties = propertyResolver.loadProperties(repositoryPath);
        log.info("Loaded {} configuration properties", properties.size());

        Launcher launcher = new Launcher();
        launcher.addInputResource(repositoryPath.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setCommentEnabled(false);

        CtModel model = launcher.buildModel();

        // Find all Spring Boot applications in the repository (handles monorepo)
        List<CtType<?>> springBootApps = findAllSpringBootApplications(model);

        if (springBootApps.isEmpty()) {
            log.warn("No Spring Boot application found in repository");
            // Return single result for non-Spring Boot project
            return List.of(createNonSpringBootAnalysis(model, projectId, repoUrl, properties));
        }

        log.info("Found {} Spring Boot application(s) in repository", springBootApps.size());

        List<CodeAnalysisResponse> responses = new ArrayList<>();

        // Analyze each Spring Boot application separately
        for (CtType<?> springBootApp : springBootApps) {
            CodeAnalysisResponse response = analyzeSpringBootApplication(
                    model, springBootApp, projectId, repoUrl, repositoryPath, properties
            );
            responses.add(response);
        }

        return responses;
    }

    private List<CtType<?>> findAllSpringBootApplications(CtModel model) {
        return model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isSpringBootApplication)
                .collect(Collectors.toList());
    }

    private CodeAnalysisResponse analyzeSpringBootApplication(
            CtModel model, CtType<?> springBootApp, String projectId, String repoUrl, Path repositoryPath, Map<String, String> properties) {

        String appPackage = springBootApp.getPackage().getQualifiedName();
        log.info("Analyzing Spring Boot application: {} in package: {}", springBootApp.getSimpleName(), appPackage);

        ApplicationInfo applicationInfo = createApplicationInfo(springBootApp);

        // Build @Value field mapping for this package
        Map<String, String> valueFieldMapping = buildValueFieldMapping(model, appPackage, properties);

        // Extract components that belong to this application (by package)
        List<ControllerInfo> controllers = extractControllersForPackage(model, appPackage, properties, valueFieldMapping);
        List<KafkaListenerInfo> kafkaListeners = kafkaAnalyzer.extractKafkaListenersForPackage(model, appPackage, properties, valueFieldMapping);
        List<ServiceInfo> services = extractServicesForPackage(model, appPackage, properties, valueFieldMapping);
        List<RepositoryInfo> repositories = extractRepositoriesForPackage(model, appPackage, properties, valueFieldMapping);
        List<ConfigurationInfo> configurations = extractConfigurationsForPackage(model, appPackage);

        int totalClasses = controllers.size() + kafkaListeners.size() + services.size() + repositories.size() + configurations.size();
        int totalMethods = controllers.stream().mapToInt(c -> c.getEndpoints().size()).sum() +
                kafkaListeners.stream().mapToInt(k -> k.getListeners().size()).sum() +
                services.stream().mapToInt(s -> s.getMethods().size()).sum() +
                repositories.stream().mapToInt(r -> r.getMethods().size()).sum() +
                configurations.stream().mapToInt(c -> c.getBeans().size()).sum();

        log.info("Analysis completed for {}. Controllers: {}, KafkaListeners: {}, Services: {}, Repositories: {}, Configurations: {}",
                springBootApp.getSimpleName(), controllers.size(), kafkaListeners.size(), services.size(),
                repositories.size(), configurations.size());

        return CodeAnalysisResponse.builder()
                .projectId(projectId)
                .repoUrl(repoUrl)
                .analyzedAt(java.time.LocalDateTime.now())
                .applicationInfo(applicationInfo)
                .controllers(controllers)
                .kafkaListeners(kafkaListeners)
                .services(services)
                .repositories(repositories)
                .configurations(configurations)
                .totalClasses(totalClasses)
                .totalMethods(totalMethods)
                .status("COMPLETED")
                .build();
    }

    private CodeAnalysisResponse createNonSpringBootAnalysis(CtModel model, String projectId, String repoUrl, Map<String, String> properties) {
        ApplicationInfo applicationInfo = ApplicationInfo.builder()
                .isSpringBootApplication(false)
                .build();

        Map<String, String> valueFieldMapping = buildValueFieldMapping(model, "", properties);

        List<ControllerInfo> controllers = extractControllers(model, properties, valueFieldMapping);
        List<KafkaListenerInfo> kafkaListeners = kafkaAnalyzer.extractKafkaListeners(model, properties, valueFieldMapping);
        List<ServiceInfo> services = extractServices(model, properties, valueFieldMapping);
        List<RepositoryInfo> repositories = extractRepositories(model, properties, valueFieldMapping);
        List<ConfigurationInfo> configurations = extractConfigurations(model);

        int totalClasses = controllers.size() + kafkaListeners.size() + services.size() + repositories.size() + configurations.size();
        int totalMethods = controllers.stream().mapToInt(c -> c.getEndpoints().size()).sum() +
                kafkaListeners.stream().mapToInt(k -> k.getListeners().size()).sum() +
                services.stream().mapToInt(s -> s.getMethods().size()).sum() +
                repositories.stream().mapToInt(r -> r.getMethods().size()).sum() +
                configurations.stream().mapToInt(c -> c.getBeans().size()).sum();

        return CodeAnalysisResponse.builder()
                .projectId(projectId)
                .repoUrl(repoUrl)
                .analyzedAt(java.time.LocalDateTime.now())
                .applicationInfo(applicationInfo)
                .controllers(controllers)
                .kafkaListeners(kafkaListeners)
                .services(services)
                .repositories(repositories)
                .configurations(configurations)
                .totalClasses(totalClasses)
                .totalMethods(totalMethods)
                .status("COMPLETED")
                .build();
    }

    private ApplicationInfo createApplicationInfo(CtType<?> springBootApp) {
        return ApplicationInfo.builder()
                .isSpringBootApplication(true)
                .mainClassName(springBootApp.getSimpleName())
                .mainClassPackage(springBootApp.getPackage().getQualifiedName())
                .rootPath(springBootApp.getPosition().getFile().getAbsolutePath())
                .line(createLineRange(springBootApp))
                .build();
    }

    /**
     * Build a mapping of field variable names to their resolved @Value property values
     * Example: marksTopic -> "marks-topic"
     */
    private Map<String, String> buildValueFieldMapping(CtModel model, String basePackage, Map<String, String> properties) {
        Map<String, String> fieldMapping = new HashMap<>();

        log.info("Building @Value field mapping for package: {}", basePackage.isEmpty() ? "ALL" : basePackage);

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(type -> basePackage.isEmpty() || belongsToPackage(type, basePackage))
                .forEach(ctClass -> {
                    ctClass.getFields().forEach(field -> {
                        // Check if field has @Value annotation
                        field.getAnnotations().forEach(annotation -> {
                            if ("Value".equals(annotation.getAnnotationType().getSimpleName())) {
                                try {
                                    Object valueObj = annotation.getValue("value");
                                    if (valueObj != null) {
                                        String placeholder = valueObj.toString();
                                        // Remove quotes if present
                                        placeholder = placeholder.replaceAll("^\"|\"$", "");

                                        // Resolve the placeholder
                                        String resolvedValue = propertyResolver.resolveProperty(placeholder, properties);

                                        // Map field name to resolved value
                                        String fieldName = field.getSimpleName();
                                        String qualifiedFieldName = ctClass.getQualifiedName() + "." + fieldName;

                                        fieldMapping.put(qualifiedFieldName, resolvedValue);
                                        log.info("✓ Mapped @Value field: {} = {} -> {}",
                                                 qualifiedFieldName, placeholder, resolvedValue);
                                    }
                                } catch (Exception e) {
                                    log.warn("✗ Could not resolve @Value for field {}.{}: {}",
                                             ctClass.getQualifiedName(), field.getSimpleName(), e.getMessage());
                                }
                            }
                        });
                    });
                });

        log.info("Built @Value field mapping with {} entries", fieldMapping.size());
        return fieldMapping;
    }

    private List<ControllerInfo> extractControllers(CtModel model, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<ControllerInfo> controllers = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isController)
                .forEach(ctClass -> {
                    ControllerInfo controllerInfo = ControllerInfo.builder()
                            .className(ctClass.getSimpleName())
                            .packageName(ctClass.getPackage().getQualifiedName())
                            .line(createLineRange(ctClass))
                            .endpoints(extractEndpoints(ctClass, properties, valueFieldMapping))
                            .build();
                    controllers.add(controllerInfo);
                });

        return controllers;
    }


    private List<ControllerInfo> extractControllersForPackage(CtModel model, String basePackage, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<ControllerInfo> controllers = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isController)
                .filter(ctClass -> belongsToPackage(ctClass, basePackage))
                .forEach(ctClass -> {
                    ControllerInfo controllerInfo = ControllerInfo.builder()
                            .className(ctClass.getSimpleName())
                            .packageName(ctClass.getPackage().getQualifiedName())
                            .line(createLineRange(ctClass))
                            .endpoints(extractEndpoints(ctClass, properties, valueFieldMapping))
                            .build();
                    controllers.add(controllerInfo);
                });

        return controllers;
    }

    private List<ServiceInfo> extractServicesForPackage(CtModel model, String basePackage, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<ServiceInfo> services = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isService)
                .filter(ctClass -> belongsToPackage(ctClass, basePackage))
                .forEach(ctClass -> {
                    ServiceInfo serviceInfo = ServiceInfo.builder()
                            .className(ctClass.getSimpleName())
                            .packageName(ctClass.getPackage().getQualifiedName())
                            .line(createLineRange(ctClass))
                            .methods(extractMethods(ctClass, properties, valueFieldMapping))
                            .build();
                    services.add(serviceInfo);
                });

        return services;
    }

    private List<RepositoryInfo> extractRepositoriesForPackage(CtModel model, String basePackage, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<RepositoryInfo> repositories = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(type -> type.isInterface() || type.isClass())
                .filter(this::isRepository)
                .filter(ctType -> belongsToPackage(ctType, basePackage))
                .forEach(ctType -> {
                    String extendsClass = extractExtendsClass(ctType);
                    String repositoryType = determineRepositoryType(extendsClass);

                    DatabaseOperationInfo dbOps = extractDatabaseOperations(ctType, model, repositoryType);

                    RepositoryInfo repoInfo = RepositoryInfo.builder()
                            .className(ctType.getSimpleName())
                            .packageName(ctType.getPackage().getQualifiedName())
                            .repositoryType(repositoryType)
                            .extendsClass(extendsClass)
                            .line(createLineRange(ctType))
                            .methods(extractMethods(ctType, properties, valueFieldMapping))
                            .databaseOperations(dbOps)
                            .build();
                    repositories.add(repoInfo);
                });

        return repositories;
    }

    private List<ConfigurationInfo> extractConfigurationsForPackage(CtModel model, String basePackage) {
        List<ConfigurationInfo> configurations = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isConfiguration)
                .filter(ctClass -> belongsToPackage(ctClass, basePackage))
                .forEach(ctClass -> {
                    ConfigurationInfo configInfo = ConfigurationInfo.builder()
                            .className(ctClass.getSimpleName())
                            .packageName(ctClass.getPackage().getQualifiedName())
                            .line(createLineRange(ctClass))
                            .beans(extractBeans(ctClass))
                            .build();
                    configurations.add(configInfo);
                    log.debug("Found Configuration class: {} for package: {}", ctClass.getQualifiedName(), basePackage);
                });

        return configurations;
    }


    private boolean belongsToPackage(CtType<?> ctType, String basePackage) {
        String packageName = ctType.getPackage().getQualifiedName();
        return packageName.startsWith(basePackage);
    }

    private List<EndpointInfo> extractEndpoints(CtType<?> controllerClass, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        String basePath = extractBasePath(controllerClass);

        controllerClass.getMethods().forEach(method -> {
            method.getAnnotations().forEach(annotation -> {
                String annotationName = annotation.getAnnotationType().getSimpleName();
                if (MAPPING_ANNOTATIONS.contains(annotationName)) {
                    String httpMethod = ANNOTATION_TO_HTTP_METHOD.getOrDefault(annotationName, "REQUEST");
                    String path = extractPath(annotation, basePath);

                    List<MethodCall> calls = extractMethodCalls(method, properties, valueFieldMapping);
                    List<ExternalCallInfo> externalCalls = mergeExternalCalls(
                            extractExternalCalls(method, properties, valueFieldMapping),
                            collectExternalCallsFromCalls(calls)
                    );
                    List<KafkaCallInfo> kafkaCalls = kafkaAnalyzer.mergeKafkaCalls(
                            kafkaAnalyzer.extractKafkaCalls(method, properties, valueFieldMapping),
                            extractionHelper.collectKafkaCallsFromCalls(calls)
                    );

                    RequestBodyInfo requestBody = extractRequestBodyInfo(method);
                    ResponseTypeInfo response = extractResponseTypeInfo(method, httpMethod);

                    EndpointInfo endpoint = EndpointInfo.builder()
                            .method(httpMethod)
                            .path(path)
                            .handlerMethod(method.getSimpleName())
                            .line(createLineRange(method))
                            .signature(method.getSignature())
                            .calls(calls)
                            .externalCalls(externalCalls)
                            .kafkaCalls(kafkaCalls)
                            .requestBody(requestBody)
                            .response(response)
                            .build();
                    endpoints.add(endpoint);
                }
            });
        });

        return endpoints;
    }

    private List<MethodCall> extractMethodCalls(CtMethod<?> method, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<MethodCall> calls = new ArrayList<>();
        Set<String> processedCalls = new HashSet<>();

        method.getElements(element -> element instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            if (execRef.getDeclaringType() != null) {
                String className = execRef.getDeclaringType().getQualifiedName();
                String methodName = execRef.getSimpleName();
                String callKey = className + "." + methodName;

                // Avoid duplicate calls
                if (!processedCalls.contains(callKey) && !isJavaStandardLibrary(className)) {
                    processedCalls.add(callKey);

                    List<ExternalCallInfo> nestedExternalCalls = new ArrayList<>();
                    List<KafkaCallInfo> nestedKafkaCalls = new ArrayList<>();
                    try {
                        CtExecutable<?> declaration = execRef.getExecutableDeclaration();
                        if (declaration instanceof CtMethod) {
                            nestedExternalCalls = extractExternalCalls((CtMethod<?>) declaration, properties, valueFieldMapping);
                            nestedKafkaCalls = kafkaAnalyzer.extractKafkaCalls((CtMethod<?>) declaration, properties, valueFieldMapping);
                        }
                    } catch (Exception e) {
                        // ignore - declaration may be unavailable
                    }

                    MethodCall methodCall = MethodCall.builder()
                            .className(className)
                            .handlerMethod(methodName)
                            .signature(execRef.getSignature())
                            .line(createLineRange(invocation))
                            .calls(extractNestedCalls(execRef, properties, valueFieldMapping))
                            .externalCalls(nestedExternalCalls)
                            .kafkaCalls(nestedKafkaCalls)
                            .build();
                    calls.add(methodCall);
                }
            }
        });

        return calls;
    }

    private List<MethodCall> extractNestedCalls(CtExecutableReference<?> execRef, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        try {
            CtExecutable<?> declaration = execRef.getExecutableDeclaration();
            if (declaration instanceof CtMethod) {
                return extractMethodCalls((CtMethod<?>) declaration, properties, valueFieldMapping);
            }
        } catch (Exception e) {
            // Declaration not found or error parsing
            log.debug("Could not extract nested calls for: {}", execRef.getSignature());
        }
        return new ArrayList<>();
    }

    private List<ServiceInfo> extractServices(CtModel model, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<ServiceInfo> services = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isService)
                .forEach(ctClass -> {
                    ServiceInfo serviceInfo = ServiceInfo.builder()
                            .className(ctClass.getSimpleName())
                            .packageName(ctClass.getPackage().getQualifiedName())
                            .line(createLineRange(ctClass))
                            .methods(extractMethods(ctClass, properties, valueFieldMapping))
                            .build();
                    services.add(serviceInfo);
                });

        return services;
    }

    private List<RepositoryInfo> extractRepositories(CtModel model, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<RepositoryInfo> repositories = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(type -> type.isInterface() || type.isClass())
                .filter(this::isRepository)
                .forEach(ctType -> {
                    String extendsClass = extractExtendsClass(ctType);
                    String repositoryType = determineRepositoryType(extendsClass);

                    RepositoryInfo repoInfo = RepositoryInfo.builder()
                            .className(ctType.getSimpleName())
                            .packageName(ctType.getPackage().getQualifiedName())
                            .repositoryType(repositoryType)
                            .extendsClass(extendsClass)
                            .line(createLineRange(ctType))
                            .methods(extractMethods(ctType, properties, valueFieldMapping))
                            .build();
                    repositories.add(repoInfo);
                });

        return repositories;
    }

    private List<MethodInfo> extractMethods(CtType<?> ctType, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        return ctType.getMethods().stream()
                .filter(method -> !method.isPrivate())
                .map(method -> {
                    List<MethodCall> calls = extractMethodCalls(method, properties, valueFieldMapping);
                    List<ExternalCallInfo> externalCalls = mergeExternalCalls(
                            extractExternalCalls(method, properties, valueFieldMapping),
                            collectExternalCallsFromCalls(calls)
                    );
                    List<KafkaCallInfo> kafkaCalls = kafkaAnalyzer.mergeKafkaCalls(
                            kafkaAnalyzer.extractKafkaCalls(method, properties, valueFieldMapping),
                            extractionHelper.collectKafkaCallsFromCalls(calls)
                    );

                    return MethodInfo.builder()
                            .methodName(method.getSimpleName())
                            .signature(method.getSignature())
                            .line(createLineRange(method))
                            .calls(calls)
                            .externalCalls(externalCalls)
                            .kafkaCalls(kafkaCalls)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private boolean isController(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(annotation -> {
                    String name = annotation.getAnnotationType().getSimpleName();
                    return name.equals("RestController") || name.equals("Controller");
                });
    }

    private boolean isService(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getAnnotationType().getSimpleName().equals("Service"));
    }

    private boolean isRepository(CtType<?> ctType) {
        // Check for @Repository annotation
        boolean hasAnnotation = ctType.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getAnnotationType().getSimpleName().equals("Repository"));

        // Check if extends *Repository interface
        boolean extendsRepository = false;
        if (ctType.isInterface()) {
            CtTypeReference<?> superInterface = ctType.getSuperInterfaces().stream()
                    .filter(si -> si.getSimpleName().endsWith("Repository"))
                    .findFirst()
                    .orElse(null);
            extendsRepository = superInterface != null;
        }

        return hasAnnotation || extendsRepository;
    }

    private String extractBasePath(CtType<?> controllerClass) {
        return controllerClass.getAnnotations().stream()
                .filter(annotation -> annotation.getAnnotationType().getSimpleName().equals("RequestMapping"))
                .findFirst()
                .map(annotation -> {
                    Object value = annotation.getValues().get("value");
                    if (value != null) {
                        String path = value.toString().replaceAll("[\"\\[\\]]", "");
                        return path.isEmpty() ? "" : path;
                    }
                    return "";
                })
                .orElse("");
    }

    private String extractPath(CtAnnotation<?> annotation, String basePath) {
        Object value = annotation.getValues().get("value");
        if (value == null) {
            value = annotation.getValues().get("path");
        }

        String path = "";
        if (value != null) {
            path = value.toString().replaceAll("[\"\\[\\]]", "");
        }

        if (basePath.isEmpty()) {
            return path.isEmpty() ? "/" : path;
        }

        return basePath + (path.isEmpty() ? "" : path);
    }

    private String extractExtendsClass(CtType<?> ctType) {
        if (ctType.isInterface()) {
            return ctType.getSuperInterfaces().stream()
                    .map(CtTypeReference::getQualifiedName)
                    .filter(name -> name.contains("Repository"))
                    .findFirst()
                    .orElse("None");
        }
        return "None";
    }

    private String determineRepositoryType(String extendsClass) {
        if (extendsClass.contains("MongoRepository")) {
            return "MongoDB";
        } else if (extendsClass.contains("JpaRepository") || extendsClass.contains("CrudRepository")) {
            return "JPA";
        } else if (extendsClass.contains("ReactiveMongoRepository")) {
            return "Reactive MongoDB";
        } else if (extendsClass.contains("ReactiveCrudRepository")) {
            return "Reactive JPA";
        }
        return "Custom";
    }

    private LineRange createLineRange(CtElement element) {
        try {
            int startLine = element.getPosition().getLine();
            int endLine = element.getPosition().getEndLine();
            return LineRange.builder()
                    .start(startLine)
                    .end(endLine)
                    .build();
        } catch (Exception e) {
            return LineRange.builder().start(0).end(0).build();
        }
    }

    private boolean isJavaStandardLibrary(String className) {
        return className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("jakarta.") ||
                className.startsWith("org.springframework.") ||
                className.startsWith("lombok.");
    }

    private ApplicationInfo extractApplicationInfo(CtModel model, Path repositoryPath) {
        log.info("Extracting Spring Boot application information");

        ApplicationInfo.ApplicationInfoBuilder builder = ApplicationInfo.builder()
                .isSpringBootApplication(false);

        // Find class with @SpringBootApplication annotation
        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isSpringBootApplication)
                .findFirst()
                .ifPresent(mainClass -> {
                    builder.isSpringBootApplication(true)
                            .mainClassName(mainClass.getSimpleName())
                            .mainClassPackage(mainClass.getPackage().getQualifiedName())
                            .rootPath(mainClass.getPosition().getFile().getAbsolutePath())
                            .line(createLineRange(mainClass));
                    log.info("Found Spring Boot Application: {}", mainClass.getQualifiedName());
                });

        return builder.build();
    }

    private boolean isSpringBootApplication(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getAnnotationType().getSimpleName()
                        .equals("SpringBootApplication"));
    }

    private List<ConfigurationInfo> extractConfigurations(CtModel model) {
        List<ConfigurationInfo> configurations = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isConfiguration)
                .forEach(ctClass -> {
                    ConfigurationInfo configInfo = ConfigurationInfo.builder()
                            .className(ctClass.getSimpleName())
                            .packageName(ctClass.getPackage().getQualifiedName())
                            .line(createLineRange(ctClass))
                            .beans(extractBeans(ctClass))
                            .build();
                    configurations.add(configInfo);
                    log.info("Found Configuration class: {}", ctClass.getQualifiedName());
                });

        return configurations;
    }

    private boolean isConfiguration(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(annotation -> {
                    String name = annotation.getAnnotationType().getSimpleName();
                    return name.equals("Configuration") || name.equals("SpringBootApplication");
                });
    }

    private List<BeanInfo> extractBeans(CtType<?> configClass) {
        List<BeanInfo> beans = new ArrayList<>();

        configClass.getMethods().forEach(method -> {
            method.getAnnotations().forEach(annotation -> {
                if (annotation.getAnnotationType().getSimpleName().equals("Bean")) {
                    BeanInfo bean = BeanInfo.builder()
                            .beanName(method.getSimpleName())
                            .returnType(method.getType().getQualifiedName())
                            .methodName(method.getSimpleName())
                            .line(createLineRange(method))
                            .build();
                    beans.add(bean);
                }
            });
        });

        return beans;
    }

    private List<ExternalCallInfo> extractExternalCalls(CtMethod<?> method, Map<String, String> properties, Map<String, String> valueFieldMapping) {
        List<ExternalCallInfo> externalCalls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> feignClients = findFeignClientTypes(method.getFactory().getModel());

        method.getElements(element -> element instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            String declaringType = execRef.getDeclaringType() != null
                    ? execRef.getDeclaringType().getQualifiedName()
                    : "";
            String methodName = execRef.getSimpleName();

            ExternalCallInfo info = null;

            if (isRestTemplateCall(invocation, declaringType, methodName)) {
                info = buildRestTemplateCall(invocation, declaringType, methodName, properties, valueFieldMapping, method.getDeclaringType());
            } else if (isWebClientCall(invocation, declaringType, methodName)) {
                info = buildWebClientCall(invocation, declaringType, methodName, properties, valueFieldMapping, method.getDeclaringType());
            } else if (isFeignClientCall(declaringType, feignClients)) {
                info = buildFeignCall(invocation, execRef, declaringType);
            }

            if (info != null) {
                String key = info.getClientType() + ":" + info.getTargetClass() + ":" + info.getTargetMethod() + ":" + info.getUrl();
                if (seen.add(key)) {
                    externalCalls.add(info);
                }
            }
        });

        return externalCalls;
    }

    private boolean isRestTemplateCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        if (declaringType.endsWith("RestTemplate") && REST_TEMPLATE_METHODS.contains(methodName)) {
            return true;
        }
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            String typeName = target.getType().getQualifiedName();
            if (typeName.endsWith("RestTemplate") && REST_TEMPLATE_METHODS.contains(methodName)) {
                return true;
            }
        }
        // Fallback when no classpath: look at target variable name
        if (target != null) {
            String targetText = target.toString().toLowerCase(Locale.ROOT);
            return targetText.contains("resttemplate") && REST_TEMPLATE_METHODS.contains(methodName);
        }
        return false;
    }

    private boolean isWebClientCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        if (declaringType.endsWith("WebClient")) {
            return WEBCLIENT_HTTP_METHODS.contains(methodName) || "uri".equals(methodName);
        }
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            return target.getType().getQualifiedName().endsWith("WebClient") ||
                    (target.getType().getQualifiedName().contains("WebClient") && WEBCLIENT_HTTP_METHODS.contains(methodName));
        }
        return false;
    }

    private boolean isFeignClientCall(String declaringType, Set<String> feignClients) {
        return feignClients.contains(declaringType);
    }

    private ExternalCallInfo buildRestTemplateCall(CtInvocation<?> invocation, String declaringType, String methodName,
                                                    Map<String, String> properties, Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        String httpMethod = restTemplateHttpMethod(methodName, invocation);
        String url = extractUrlLiteral(invocation.getArguments(), properties, valueFieldMapping, declaringClass);

        return ExternalCallInfo.builder()
                .clientType("RestTemplate")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaringType)
                .targetMethod(methodName)
                .line(createLineRange(invocation))
                .resolved(false)
                .build();
    }

    private ExternalCallInfo buildWebClientCall(CtInvocation<?> invocation, String declaringType, String methodName,
                                                 Map<String, String> properties, Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        String httpMethod = methodName.toUpperCase(Locale.ROOT);
        if (!WEBCLIENT_HTTP_METHODS.contains(methodName)) {
            httpMethod = "UNKNOWN";
        }
        String url = extractUrlFromWebClientChain(invocation, properties, valueFieldMapping, declaringClass);

        return ExternalCallInfo.builder()
                .clientType("WebClient")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaringType)
                .targetMethod(methodName)
                .line(createLineRange(invocation))
                .resolved(false)
                .build();
    }

    private ExternalCallInfo buildFeignCall(CtInvocation<?> invocation, CtExecutableReference<?> execRef, String declaringType) {
        String httpMethod = "REQUEST";
        String url = "<dynamic>";

        CtExecutable<?> decl = execRef.getExecutableDeclaration();
        if (decl instanceof CtMethod) {
            CtMethod<?> feignMethod = (CtMethod<?>) decl;
            String basePath = extractBasePath(feignMethod.getDeclaringType());
            for (CtAnnotation<?> annotation : feignMethod.getAnnotations()) {
                String annotationName = annotation.getAnnotationType().getSimpleName();
                if (MAPPING_ANNOTATIONS.contains(annotationName)) {
                    httpMethod = ANNOTATION_TO_HTTP_METHOD.getOrDefault(annotationName, "REQUEST");
                    url = extractPath(annotation, basePath);
                    break;
                }
            }
        }

        return ExternalCallInfo.builder()
                .clientType("Feign")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaringType)
                .targetMethod(execRef.getSimpleName())
                .line(createLineRange(invocation))
                .resolved(false)
                .build();
    }

    private String restTemplateHttpMethod(String methodName, CtInvocation<?> invocation) {
        if ("exchange".equals(methodName)) {
            String httpMethod = extractHttpMethodLiteral(invocation.getArguments());
            return httpMethod != null ? httpMethod : "REQUEST";
        }
        if (methodName.startsWith("get")) return "GET";
        if (methodName.startsWith("post")) return "POST";
        if ("put".equals(methodName)) return "PUT";
        if ("delete".equals(methodName)) return "DELETE";
        return "REQUEST";
    }

    private String extractUrlLiteral(List<? extends CtExpression<?>> arguments, Map<String, String> properties,
                                      Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        for (CtExpression<?> arg : arguments) {
            String extracted = extractStringFromExpression(arg, properties, valueFieldMapping, declaringClass);
            if (extracted != null && !extracted.isBlank()) {
                return normalizeUrl(extracted);
            }
        }
        return "<dynamic>";
    }

    private String extractStringFromExpression(CtExpression<?> expression, Map<String, String> properties,
                                                 Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        if (expression == null) {
            return null;
        }

        // Log what type of expression we're processing
        log.info("🔍 Extracting string from expression type: {}, value: {}, declaringClass: {}, valueFieldMapping size: {}",
                  expression.getClass().getSimpleName(),
                  expression.toString(),
                  declaringClass != null ? declaringClass.getQualifiedName() : "null",
                  valueFieldMapping != null ? valueFieldMapping.size() : "NULL");

        if (expression instanceof CtLiteral) {
            Object value = ((CtLiteral<?>) expression).getValue();
            if (value instanceof String) {
                log.info("✓ Found literal string: {}", value);
                return (String) value;
            }
        }

        // Handle CtFieldRead (field access like marksTopic)
        if (expression instanceof CtFieldRead) {
            log.info("🎯 Detected CtFieldRead expression!");
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) expression;

            try {
                CtFieldReference<?> fieldRef = fieldRead.getVariable();
                if (fieldRef != null) {
                    String fieldName = fieldRef.getSimpleName();
                    CtTypeReference<?> declaringType = fieldRef.getDeclaringType();

                    if (declaringType != null) {
                        String fieldClassName = declaringType.getQualifiedName();
                        String qualifiedFieldName = fieldClassName + "." + fieldName;

                        log.info("🎯 Found CtFieldRead: field={}, declaring class={}, qualified={}",
                                 fieldName, fieldClassName, qualifiedFieldName);
                        log.info("🔍 Looking up in valueFieldMapping with {} entries", valueFieldMapping.size());

                        // Try exact match
                        String resolvedValue = valueFieldMapping.get(qualifiedFieldName);
                        if (resolvedValue != null && !resolvedValue.startsWith("${")) {
                            log.info("✅ SUCCESS! Resolved CtFieldRead {} to {}", qualifiedFieldName, resolvedValue);
                            return resolvedValue;
                        } else {
                            log.warn("❌ Failed exact match for: {}, resolved={}", qualifiedFieldName, resolvedValue);
                        }

                        // Try suffix match
                        log.info("🔍 Trying suffix match for field: {}", fieldName);
                        for (Map.Entry<String, String> entry : valueFieldMapping.entrySet()) {
                            if (entry.getKey().endsWith("." + fieldName)) {
                                log.info("✅ SUCCESS! Resolved CtFieldRead (by suffix) {} to {}", entry.getKey(), entry.getValue());
                                return entry.getValue();
                            }
                        }

                        log.warn("❌ No suffix match found for field: {}", fieldName);
                    }
                }
            } catch (Exception e) {
                log.error("❌ Error extracting field from CtFieldRead: {}", e.getMessage(), e);
            }
        }

        if (expression instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binary = (CtBinaryOperator<?>) expression;
            if (binary.getKind() == BinaryOperatorKind.PLUS) {
                String left = extractStringFromExpression(binary.getLeftHandOperand(), properties, valueFieldMapping, declaringClass);
                String right = extractStringFromExpression(binary.getRightHandOperand(), properties, valueFieldMapping, declaringClass);
                return joinUrlPieces(left, right);
            }
        }

        // Try to resolve field references (e.g., userServiceUrl, marksTopic)
        String text = expression.toString();
        if (text != null && !text.isBlank()) {
            // Check if this is a simple field reference (single identifier without dots)
            String fieldName = text.trim();

            // Try direct lookup first - field name with declaring class
            String qualifiedFieldName = declaringClass != null ?
                    declaringClass.getQualifiedName() + "." + fieldName : fieldName;

            log.debug("Attempting to resolve field reference: {} (qualified: {})", fieldName, qualifiedFieldName);

            // Try to resolve from valueFieldMapping with qualified name
            String resolvedValue = valueFieldMapping.get(qualifiedFieldName);
            if (resolvedValue != null && !resolvedValue.startsWith("${")) {
                log.info("✓ Resolved field reference {} to {}", qualifiedFieldName, resolvedValue);
                return resolvedValue;
            }

            // If still a placeholder, try to resolve it
            if (resolvedValue != null && propertyResolver.hasPlaceholders(resolvedValue)) {
                String fullyResolved = propertyResolver.resolveProperty(resolvedValue, properties);
                if (fullyResolved != null && !fullyResolved.startsWith("${")) {
                    log.info("✓ Fully resolved field reference {} to {}", qualifiedFieldName, fullyResolved);
                    return fullyResolved;
                }
            }

            // Try without the declaring class prefix (for same-class fields)
            resolvedValue = valueFieldMapping.get(fieldName);
            if (resolvedValue != null && !resolvedValue.startsWith("${")) {
                log.info("✓ Resolved field reference (simple name) {} to {}", fieldName, resolvedValue);
                return resolvedValue;
            }

            // Final fallback: search for any field ending with this name
            for (Map.Entry<String, String> entry : valueFieldMapping.entrySet()) {
                if (entry.getKey().endsWith("." + fieldName)) {
                    log.info("✓ Resolved field reference (by suffix match) {} to {}", entry.getKey(), entry.getValue());
                    return entry.getValue();
                }
            }

            // Log failure with all available mappings for debugging
            log.warn("✗ Could not resolve field reference: '{}' (qualified: '{}')", fieldName, qualifiedFieldName);
            log.warn("   Tried: exact match '{}', simple name '{}', and suffix matches", qualifiedFieldName, fieldName);
            if (!valueFieldMapping.isEmpty()) {
                log.warn("   Available @Value field mappings ({} total):", valueFieldMapping.size());
                valueFieldMapping.forEach((key, value) ->
                    log.warn("     - {} = {}", key, value)
                );
            } else {
                log.warn("   No @Value field mappings available!");
            }

            return "<dynamic>";
        }
        return null;
    }

    private String joinUrlPieces(String left, String right) {
        String safeLeft = left == null ? "<dynamic>" : left;
        String safeRight = right == null ? "<dynamic>" : right;

        if ("<dynamic>".equals(safeLeft) && safeRight.startsWith("/")) {
            return safeRight;
        }
        if ("<dynamic>".equals(safeRight) && safeLeft.contains("/")) {
            return safeLeft + "<dynamic>";
        }
        return safeLeft + safeRight;
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "<dynamic>";
        }
        if ("<dynamic>".equals(url)) {
            return url;
        }
        if (url.startsWith("<dynamic>/")) {
            return url.substring("<dynamic>".length());
        }
        return url;
    }

    private String extractUrlFromWebClientChain(CtInvocation<?> invocation, Map<String, String> properties,
                                                 Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        // Walk invocation target chain to find uri("...") call
        CtExpression<?> target = invocation.getTarget();
        while (target instanceof CtInvocation) {
            CtInvocation<?> targetInvocation = (CtInvocation<?>) target;
            if ("uri".equals(targetInvocation.getExecutable().getSimpleName())) {
                return extractUrlLiteral(targetInvocation.getArguments(), properties, valueFieldMapping, declaringClass);
            }
            target = targetInvocation.getTarget();
        }
        return "<dynamic>";
    }

    private Set<String> findFeignClientTypes(CtModel model) {
        Set<String> feignClients = new HashSet<>();
        model.getAllTypes().stream()
                .filter(CtType::isInterface)
                .forEach(ctType -> {
                    boolean isFeign = ctType.getAnnotations().stream()
                            .anyMatch(a -> a.getAnnotationType().getSimpleName().equals("FeignClient"));
                    if (isFeign) {
                        feignClients.add(ctType.getQualifiedName());
                    }
                });
        return feignClients;
    }

    private List<ExternalCallInfo> collectExternalCallsFromCalls(List<MethodCall> calls) {
        List<ExternalCallInfo> externalCalls = new ArrayList<>();
        if (calls == null) {
            return externalCalls;
        }
        for (MethodCall call : calls) {
            if (call.getExternalCalls() != null) {
                externalCalls.addAll(call.getExternalCalls());
            }
            if (call.getCalls() != null && !call.getCalls().isEmpty()) {
                externalCalls.addAll(collectExternalCallsFromCalls(call.getCalls()));
            }
        }
        return externalCalls;
    }

    private List<ExternalCallInfo> mergeExternalCalls(
            List<ExternalCallInfo> direct,
            List<ExternalCallInfo> nested) {
        List<ExternalCallInfo> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (direct != null) {
            for (ExternalCallInfo call : direct) {
                String key = call.getClientType() + ":" + call.getTargetClass() + ":" + call.getTargetMethod() + ":" + call.getUrl();
                if (seen.add(key)) {
                    merged.add(call);
                }
            }
        }
        if (nested != null) {
            for (ExternalCallInfo call : nested) {
                String key = call.getClientType() + ":" + call.getTargetClass() + ":" + call.getTargetMethod() + ":" + call.getUrl();
                if (seen.add(key)) {
                    merged.add(call);
                }
            }
        }
        return merged;
    }

    private String extractHttpMethodLiteral(List<? extends CtExpression<?>> arguments) {
        for (CtExpression<?> arg : arguments) {
            if (arg instanceof CtLiteral) {
                Object value = ((CtLiteral<?>) arg).getValue();
                if (value instanceof String) {
                    return value.toString();
                }
            }
            if (arg != null && arg.toString().contains("HttpMethod")) {
                return arg.toString().replace("HttpMethod.", "");
            }
        }
        return null;
    }

    private RequestBodyInfo extractRequestBodyInfo(CtMethod<?> method) {
        try {
            for (CtParameter parameter : method.getParameters()) {
                // Check if parameter has @RequestBody annotation
                boolean hasRequestBody = parameter.getAnnotations().stream()
                        .anyMatch(a -> a.getAnnotationType().getSimpleName().equals("RequestBody"));

                if (hasRequestBody) {
                    CtTypeReference<?> paramType = parameter.getType();
                    String typeName = paramType.getQualifiedName();
                    String simpleTypeName = paramType.getSimpleName();
                    boolean isCollection = typeName.contains("List") || typeName.contains("Collection");

                    List<String> fields = new ArrayList<>();
                    try {
                        CtType<?> paramClass = paramType.getTypeDeclaration();
                        if (paramClass != null && paramClass.isClass()) {
                            paramClass.getFields().stream()
                                    .filter(f -> !f.isPrivate())
                                    .map(CtField::getSimpleName)
                                    .forEach(fields::add);
                        }
                    } catch (Exception e) {
                        log.debug("Could not extract fields for type: {}", typeName);
                    }

                    return RequestBodyInfo.builder()
                            .type(typeName)
                            .simpleTypeName(simpleTypeName)
                            .fields(fields)
                            .isCollection(isCollection)
                            .isWrapper(true)
                            .build();
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting request body info: {}", e.getMessage());
        }

        return null;
    }

    private ResponseTypeInfo extractResponseTypeInfo(CtMethod<?> method, String httpMethod) {
        try {
            CtTypeReference<?> returnType = method.getType();

            if (returnType == null || "void".equals(returnType.getSimpleName())) {
                return ResponseTypeInfo.builder()
                        .statusCode(204)
                        .isCollection(false)
                        .build();
            }

            String typeName = returnType.getQualifiedName();
            String simpleTypeName = returnType.getSimpleName();
            boolean isCollection = typeName.contains("List") || typeName.contains("Collection") || typeName.contains("ResponseEntity<List");

            // Extract status code based on HTTP method
            int statusCode = 200; // default
            if ("POST".equals(httpMethod)) {
                statusCode = 201;
            }

            List<String> fields = new ArrayList<>();
            try {
                CtType<?> returnClass = returnType.getTypeDeclaration();
                if (returnClass != null && returnClass.isClass()) {
                    returnClass.getFields().stream()
                            .filter(f -> !f.isPrivate())
                            .map(CtField::getSimpleName)
                            .forEach(fields::add);
                }
            } catch (Exception e) {
                log.debug("Could not extract fields for return type: {}", typeName);
            }

            return ResponseTypeInfo.builder()
                    .type(typeName)
                    .simpleTypeName(simpleTypeName)
                    .fields(fields)
                    .isCollection(isCollection)
                    .statusCode(statusCode)
                    .build();

        } catch (Exception e) {
            log.debug("Error extracting response type info: {}", e.getMessage());
        }

        return ResponseTypeInfo.builder()
                .statusCode(200)
                .isCollection(false)
                .build();
    }

    private DatabaseOperationInfo extractDatabaseOperations(CtType<?> repositoryType, CtModel model, String dbType) {
        try {
            // Get generic type (entity class) from repository
            String entityClassName = extractEntityClassName(repositoryType);

            if (entityClassName == null) {
                return null;
            }

            // Find entity class in model
            CtType<?> entityClass = model.getAllTypes().stream()
                    .filter(t -> t.getQualifiedName().equals(entityClassName) ||
                                t.getSimpleName().equals(entityClassName))
                    .findFirst()
                    .orElse(null);

            if (entityClass == null) {
                log.debug("Could not find entity class: {}", entityClassName);
                return null;
            }

            // Extract table name from entity
            String tableName = extractTableName(entityClass, entityClassName);
            String tableSource = determineTableSource(entityClass, entityClassName);

            // Determine database operations based on repository methods
            List<String> operations = extractDatabaseOperations(repositoryType);

            return DatabaseOperationInfo.builder()
                    .className(repositoryType.getSimpleName())
                    .packageName(repositoryType.getPackage().getQualifiedName())
                    .entityClass(entityClassName)
                    .entitySimpleName(extractSimpleName(entityClassName))
                    .tableName(tableName)
                    .tableSource(tableSource)
                    .databaseType(dbType)
                    .operations(operations)
                    .line(createLineRange(repositoryType))
                    .build();

        } catch (Exception e) {
            log.debug("Error extracting database operations: {}", e.getMessage());
            return null;
        }
    }

    private String extractEntityClassName(CtType<?> repositoryType) {
        try {
            // For JpaRepository<Entity, ID>, extract Entity
            if (repositoryType.isInterface()) {
                for (CtTypeReference<?> superInterface : repositoryType.getSuperInterfaces()) {
                    String interfaceName = superInterface.getQualifiedName();
                    if (interfaceName.contains("Repository")) {
                        // Get generic type parameters
                        try {
                            String typeString = superInterface.toString();
                            if (typeString.contains("<")) {
                                int start = typeString.indexOf("<") + 1;
                                int comma = typeString.indexOf(",");
                                if (comma > start) {
                                    return typeString.substring(start, comma).trim();
                                } else {
                                    int end = typeString.indexOf(">");
                                    if (end > start) {
                                        return typeString.substring(start, end).trim();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Could not extract generic type from: {}", superInterface);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting entity class: {}", e.getMessage());
        }
        return null;
    }

    private String extractTableName(CtType<?> entityClass, String entityClassName) {
        // Try @Table annotation
        try {
            for (CtAnnotation<?> annotation : entityClass.getAnnotations()) {
                String annotationName = annotation.getAnnotationType().getSimpleName();
                if ("Table".equals(annotationName)) {
                    Object nameValue = annotation.getValues().get("name");
                    if (nameValue != null) {
                        return nameValue.toString().replaceAll("[\"\\s]", "");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting @Table annotation: {}", e.getMessage());
        }

        // Try @Document annotation (MongoDB)
        try {
            for (CtAnnotation<?> annotation : entityClass.getAnnotations()) {
                String annotationName = annotation.getAnnotationType().getSimpleName();
                if ("Document".equals(annotationName)) {
                    Object collectionValue = annotation.getValues().get("collection");
                    if (collectionValue != null) {
                        return collectionValue.toString().replaceAll("[\"\\s]", "");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting @Document annotation: {}", e.getMessage());
        }

        // Fallback: derive from class name (convert camelCase to snake_case)
        return camelCaseToSnakeCase(extractSimpleName(entityClassName));
    }

    private String determineTableSource(CtType<?> entityClass, String entityClassName) {
        // Check for @Table annotation
        try {
            for (CtAnnotation<?> annotation : entityClass.getAnnotations()) {
                if ("Table".equals(annotation.getAnnotationType().getSimpleName())) {
                    return "@Table";
                }
            }
        } catch (Exception e) {
            log.debug("Error checking @Table: {}", e.getMessage());
        }

        // Check for @Document annotation
        try {
            for (CtAnnotation<?> annotation : entityClass.getAnnotations()) {
                if ("Document".equals(annotation.getAnnotationType().getSimpleName())) {
                    return "@Document";
                }
            }
        } catch (Exception e) {
            log.debug("Error checking @Document: {}", e.getMessage());
        }

        // Fallback: derived from class name
        return "derived_from_class_name";
    }

    private List<String> extractDatabaseOperations(CtType<?> repositoryType) {
        List<String> operations = new ArrayList<>();
        Set<String> operationSet = new HashSet<>();

        try {
            for (CtMethod<?> method : repositoryType.getMethods()) {
                String methodName = method.getSimpleName().toLowerCase(Locale.ROOT);

                // READ operations
                if (methodName.contains("find") || methodName.contains("get") ||
                    methodName.contains("read") || methodName.contains("query")) {
                    operationSet.add("READ");
                }

                // WRITE/CREATE operations
                if (methodName.contains("save") || methodName.contains("create") ||
                    methodName.contains("insert") || methodName.contains("persist")) {
                    operationSet.add("WRITE");
                }

                // UPDATE operations
                if (methodName.contains("update") || methodName.contains("merge")) {
                    operationSet.add("UPDATE");
                }

                // DELETE operations
                if (methodName.contains("delete") || methodName.contains("remove")) {
                    operationSet.add("DELETE");
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting database operations: {}", e.getMessage());
        }

        // Default if no methods found
        if (operationSet.isEmpty()) {
            operationSet.add("READ");
            operationSet.add("WRITE");
            operationSet.add("DELETE");
        }

        operations.addAll(operationSet);
        operations.sort(String::compareTo);
        return operations;
    }

    private String extractSimpleName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isEmpty()) {
            return "";
        }
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot > 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }

    private String camelCaseToSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
