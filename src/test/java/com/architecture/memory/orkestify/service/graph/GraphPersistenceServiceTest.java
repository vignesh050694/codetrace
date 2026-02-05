package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.ApplicationInfo;
import com.architecture.memory.orkestify.dto.CodeAnalysisResponse;
import com.architecture.memory.orkestify.dto.ControllerInfo;
import com.architecture.memory.orkestify.dto.EndpointInfo;
import com.architecture.memory.orkestify.dto.MethodCall;
import com.architecture.memory.orkestify.dto.MethodInfo;
import com.architecture.memory.orkestify.dto.ServiceInfo;
import com.architecture.memory.orkestify.model.graph.nodes.ApplicationNode;
import com.architecture.memory.orkestify.model.graph.nodes.ControllerNode;
import com.architecture.memory.orkestify.model.graph.nodes.EndpointNode;
import com.architecture.memory.orkestify.model.graph.nodes.MethodNode;
import com.architecture.memory.orkestify.repository.graph.ApplicationNodeRepository;
import com.architecture.memory.orkestify.repository.graph.KafkaTopicNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphPersistenceServiceTest {

    @Mock
    private ApplicationNodeRepository applicationNodeRepository;

    @Mock
    private KafkaTopicNodeRepository kafkaTopicNodeRepository;

    @InjectMocks
    private GraphPersistenceService graphPersistenceService;

    @Test
    void linksEndpointToServiceMethod_whenCallHasMatchingSignature() {
        when(applicationNodeRepository.findByProjectIdAndAppKey(any(), any()))
                .thenReturn(Optional.empty());
        when(applicationNodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MethodInfo serviceMethod = MethodInfo.builder()
                .methodName("getUserById")
                .signature("User getUserById(Long)")
                .build();

        ServiceInfo serviceInfo = ServiceInfo.builder()
                .className("com.example.UserService")
                .packageName("com.example")
                .methods(List.of(serviceMethod))
                .build();

        MethodCall serviceCall = MethodCall.builder()
                .className("com.example.UserService")
                .handlerMethod("getUserById")
                .signature("User getUserById(Long)")
                .build();

        EndpointInfo endpointInfo = EndpointInfo.builder()
                .method("GET")
                .path("/users/{id}")
                .handlerMethod("getUserById")
                .signature("UserController#getUserById(Long)")
                .calls(List.of(serviceCall))
                .build();

        ControllerInfo controllerInfo = ControllerInfo.builder()
                .className("com.example.UserController")
                .packageName("com.example")
                .endpoints(List.of(endpointInfo))
                .build();

        CodeAnalysisResponse analysis = CodeAnalysisResponse.builder()
                .projectId("project-1")
                .repoUrl("https://repo")
                .analyzedAt(LocalDateTime.now())
                .status("SUCCESS")
                .applicationInfo(ApplicationInfo.builder()
                        .mainClassName("DemoApplication")
                        .mainClassPackage("com.example")
                        .isSpringBootApplication(true)
                        .build())
                .controllers(List.of(controllerInfo))
                .services(List.of(serviceInfo))
                .totalMethods(10)
                .totalClasses(0)
                .build();

        graphPersistenceService.persistAnalysis("project-1", "user-1", "https://repo", analysis);

        ArgumentCaptor<ApplicationNode> captor = ArgumentCaptor.forClass(ApplicationNode.class);
        verify(applicationNodeRepository).save(captor.capture());
        ApplicationNode persisted = captor.getValue();

        // Navigate persisted structure
        assertThat(persisted.getControllers()).hasSize(1);
        ControllerNode controller = persisted.getControllers().iterator().next();
        assertThat(controller.getEndpoints()).hasSize(1);
        EndpointNode endpoint = controller.getEndpoints().iterator().next();

        Set<MethodNode> calls = endpoint.getCalls();
        assertThat(calls)
                .withFailMessage("Expected endpoint to link to the UserService#getUserById service method")
                .anySatisfy(method -> {
                    assertThat(method.getMethodName()).isEqualTo("getUserById");
                    assertThat(method.getClassName()).isEqualTo("com.example.UserService");
                });
    }
}
