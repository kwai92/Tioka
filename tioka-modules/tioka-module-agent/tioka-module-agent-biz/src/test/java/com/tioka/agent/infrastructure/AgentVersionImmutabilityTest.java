package com.tioka.agent.infrastructure;

import com.tioka.agent.infrastructure.mapper.AgentMapper;
import com.tioka.agent.infrastructure.mapper.AgentVersionMapper;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.framework.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentVersionImmutabilityTest {

    @Test
    void publishedVersion_rejectsUpdateById() {
        AgentMapper agents = mock(AgentMapper.class);
        AgentVersionMapper versions = mock(AgentVersionMapper.class);
        AgentRepository repository = new AgentRepository(agents, versions);

        AgentVersionDO existing = new AgentVersionDO();
        existing.setId("av_1");
        existing.setPublished(true);
        existing.setInstructions("orig");
        when(versions.selectById("av_1")).thenReturn(existing);

        AgentVersionDO mutated = new AgentVersionDO();
        mutated.setId("av_1");
        mutated.setPublished(true);
        mutated.setInstructions("mutated");

        assertThrows(BizException.class, () -> repository.upsertVersion(mutated));
        verify(versions, never()).updateById(mutated);
    }

    @Test
    void draftVersion_canBePublished() {
        AgentMapper agents = mock(AgentMapper.class);
        AgentVersionMapper versions = mock(AgentVersionMapper.class);
        AgentRepository repository = new AgentRepository(agents, versions);

        AgentVersionDO existing = new AgentVersionDO();
        existing.setId("av_2");
        existing.setPublished(false);
        when(versions.selectById("av_2")).thenReturn(existing);

        AgentVersionDO published = new AgentVersionDO();
        published.setId("av_2");
        published.setPublished(true);
        published.setSnapshotHash("abc");

        assertDoesNotThrow(() -> repository.upsertVersion(published));
        verify(versions).updateById(published);
    }
}
