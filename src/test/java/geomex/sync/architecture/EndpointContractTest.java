package geomex.sync.architecture;

import geomex.sync.settings.SettingsController;

import geomex.sync.database.DbSetupController;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointContractTest {

    private static final Class<?>[] CONTROLLERS = {
            geomex.sync.monitoring.ApiTestController.class,
            geomex.sync.gateway.ApiTestProxyController.class,
            geomex.sync.gateway.ConnStatsController.class,
            geomex.sync.monitoring.DashboardController.class,
            geomex.sync.database.DbSetupController.class,
            geomex.sync.kras.FileDownloadController.class,
            geomex.sync.gateway.KrasConnController.class,
            geomex.sync.gateway.KrasGmxController.class,
            geomex.sync.monitoring.LogController.class,
            geomex.sync.kras.MockGatewayController.class,
            geomex.sync.ods.OdsController.class,
            geomex.sync.synchronization.ScheduleController.class,
            geomex.sync.settings.SettingsController.class,
            geomex.sync.synchronization.SyncController.class
    };

    @Test
    void preservesEveryHttpMapping() throws Exception {
        assertThat(snapshot()).containsExactlyInAnyOrderElementsOf(expectedContract());
    }

    private static Set<String> snapshot() {
        Set<String> result = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(
                    controller, RequestMapping.class);
            String prefix = firstPath(typeMapping);

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                        method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }

                String verbs = mapping.method().length == 0
                        ? "ANY"
                        : Arrays.stream(mapping.method())
                                .map(RequestMethod::name)
                                .sorted()
                                .toList()
                                .toString();
                String parameters = Arrays.stream(method.getParameters())
                        .map(EndpointContractTest::parameterContract)
                        .sorted()
                        .toList()
                        .toString();
                String path = prefix + firstPath(mapping);

                result.add(controller.getSimpleName() + " | " + verbs + " "
                        + (path.isEmpty() ? "/" : path) + " | "
                        + Arrays.toString(mapping.consumes()) + " | "
                        + Arrays.toString(mapping.produces()) + " | " + parameters);
            }
        }
        return result;
    }

    private static String firstPath(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? "" : mapping.path()[0];
    }

    private static String parameterContract(Parameter parameter) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            String name = requestParam.name().isBlank() ? parameter.getName() : requestParam.name();
            return "request:" + name + ":" + requestParam.required()
                    + ":" + normalizeDefaultValue(requestParam.defaultValue());
        }

        PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String name = pathVariable.name().isBlank() ? parameter.getName() : pathVariable.name();
            return "path:" + name + ":" + pathVariable.required();
        }

        return "infrastructure:" + parameter.getType().getSimpleName();
    }

    private static String normalizeDefaultValue(String value) {
        return ValueConstants.DEFAULT_NONE.equals(value) ? "<none>" : value;
    }

    private static Set<String> expectedContract() throws Exception {
        var resource = EndpointContractTest.class.getResourceAsStream(
                "/contracts/http-endpoints.txt");
        assertThat(resource).as("recorded HTTP contract").isNotNull();
        try (resource) {
            return new LinkedHashSet<>(new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList());
        }
    }
}
