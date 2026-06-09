/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.dao.MetadataDAO;

/**
 * Unit tests for {@link RegisteredTaskDefsRegistrar}.
 *
 * <p>Each {@link RegisteredTaskDefs} bean's contribution must reach the
 * metadata DAO unmodified. Conductor's dynamic-dispatch lookup is by
 * task name; missing or mangled defs surface as
 * "Cannot find task by name X" at runtime.</p>
 */
class RegisteredTaskDefsRegistrarTest {

    @Test
    void writesEveryContributedTaskDefToTheDao() {
        MetadataDAO dao = mock(MetadataDAO.class);
        RegisteredTaskDefs supplierA = () -> List.of(def("ocg_query"), def("ocg_get_entity"));
        RegisteredTaskDefs supplierB = () -> List.of(def("another_tool"));

        new RegisteredTaskDefsRegistrar(dao, List.of(supplierA, supplierB)).registerAll();

        ArgumentCaptor<TaskDef> captor = ArgumentCaptor.forClass(TaskDef.class);
        verify(dao, times(3)).updateTaskDef(captor.capture());
        assertThat(captor.getAllValues().stream().map(TaskDef::getName))
                .containsExactlyInAnyOrder("ocg_query", "ocg_get_entity", "another_tool");
    }

    @Test
    void noSuppliersMeansNoWrites() {
        MetadataDAO dao = mock(MetadataDAO.class);

        new RegisteredTaskDefsRegistrar(dao, null).registerAll();
        new RegisteredTaskDefsRegistrar(dao, List.of()).registerAll();

        verifyNoInteractions(dao);
    }

    private static TaskDef def(String name) {
        TaskDef d = new TaskDef();
        d.setName(name);
        return d;
    }
}
