/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;
import dev.agentspan.runtime.registry.RegisteredAgent;
import dev.agentspan.runtime.registry.RegisteredAgent.ExposeAsTool;
import dev.agentspan.runtime.registry.RegisteredAgentRegistrar;

/**
 * Bootstrap-ordering regression test for the {@link RegisteredAgentRegistrar}
 * + auto-expose merger interaction.
 *
 * <p>The merger lazily caches the DAO's auto-exposed-workflow list on first
 * read and never refreshes it. If the registrar performs the cache-triggering
 * read <em>during</em> its own bootstrap loop — i.e. before the agent it's
 * registering has been written to the DAO — the cache snapshots an empty list
 * and stays empty for the bean's lifetime. The OCG sub-agent (and every future
 * server-registered agent) silently becomes invisible to user compiles.</p>
 *
 * <p>This test pins the contract by exercising the real registrar against a
 * stateful in-memory DAO and verifying that, after bootstrap, a user-side
 * merge picks up the agent the registrar just persisted.</p>
 */
class RegisteredAgentBootstrapTest {

    @Test
    void userMergeAfterBootstrapSeesAgentsTheRegistrarJustWrote() {
        // Stateful in-memory DAO — getAllWorkflowDefsLatestVersions reflects
        // whatever has been written so far. Mirrors Conductor's real
        // persistence behaviour during boot.
        MetadataDAO dao = mock(MetadataDAO.class);
        List<WorkflowDef> daoState = new ArrayList<>();
        when(dao.getAllWorkflowDefsLatestVersions()).thenAnswer(inv -> List.copyOf(daoState));
        doAnswer(inv -> {
                    daoState.add(inv.getArgument(0));
                    return null;
                })
                .when(dao)
                .updateWorkflowDef(any(WorkflowDef.class));

        AgentCompiler compiler = new AgentCompiler(dao);

        // Stub a registered agent whose compile path doesn't need a real
        // model — the AgentConfig is built with no tools and no model so it
        // routes through compileSimple (which only needs the name + builds a
        // workflow). What matters here is the order: register → write → merge.
        RegisteredAgent helper = new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                return AgentConfig.builder()
                        .name("_helper_agent")
                        .description("Test helper")
                        .model("openai/gpt-4o-mini")
                        .tools(new ArrayList<>())
                        .build();
            }

            @Override
            public ExposeAsTool autoExpose() {
                return new ExposeAsTool("helper_tool", "Call when stuck.");
            }
        };

        // Bootstrap path — this is where the cache-population bug fires
        // pre-fix: the registrar's compile() call queries the (still-empty)
        // DAO and caches an empty entry list.
        new RegisteredAgentRegistrar(compiler, dao, List.of(helper)).registerAll();

        // Sanity: the registrar wrote the auto-expose-marked def to the DAO.
        assertThat(daoState).hasSize(1);
        assertThat(daoState.get(0).getMetadata())
                .as("registrar must stamp the auto-expose metadata key")
                .containsKey(AgentCompiler.AUTO_EXPOSE_AS_TOOL_METADATA_KEY);

        // The actual contract under test: a user agent compiled AFTER bootstrap
        // must see the registered agent in its tools list. Pre-fix this fails
        // because the cache was populated empty during step 1, before the DAO
        // actually had the registered def in it.
        AgentConfig userAgent = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>())
                .build();

        compiler.mergeAutoExposedTools(userAgent);

        assertThat(userAgent.getTools())
                .extracting(ToolConfig::getName)
                .as("first user merge after bootstrap must see the just-registered agent")
                .contains("helper_tool");
    }
}
