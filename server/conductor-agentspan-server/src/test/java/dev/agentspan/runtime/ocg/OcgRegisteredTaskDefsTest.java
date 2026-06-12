/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class OcgRegisteredTaskDefsTest {

    @Test
    void taskDefNamesMatchDispatchedTaskNames() {
        // The enrich script schedules each OCG tool call as a task named
        // ``taskType.toLowerCase()`` (e.g. OCG_QUERY → "ocg_query"). Conductor
        // resolves dynamic-fork tasks by NAME, so the registered TaskDefs must
        // use exactly those names — anything else fails dispatch with
        // "Cannot find task by name ocg_query in the task definitions".
        List<String> names = new OcgRegisteredTaskDefs()
                .taskDefs().stream().map(def -> def.getName()).toList();

        assertThat(names)
                .containsExactlyInAnyOrder(
                        "ocg_query",
                        "ocg_get_entity",
                        "ocg_neighborhood",
                        "ocg_code_history",
                        "ocg_memory_set",
                        "ocg_memory_reinforce",
                        "ocg_memory_delete");
    }
}
