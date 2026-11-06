package com.tioka.agent.infrastructure;

import com.tioka.agent.infrastructure.mapper.AgentMapper;
import com.tioka.agent.infrastructure.mapper.AgentVersionMapper;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentVersionImmutabilityTest {

    @Test
    void insertOnly_noGenericUpdateApi() {
        AgentMapper agents = mock(AgentMapper.class);
        AgentVersionMapper versions = mock(AgentVersionMapper.class);
        AgentRepository repository = new AgentRepository(agents, versions);

        AgentVersionDO row = new AgentVersionDO();
        row.setId("av_1");
        row.setStatus("PUBLISHED");
        repository.insertVersion(row);
        verify(versions).insert(row);
    }

    @Test
    void retire_allowsStatusUpdate() {
        AgentMapper agents = mock(AgentMapper.class);
        AgentVersionMapper versions = mock(AgentVersionMapper.class);
        AgentRepository repository = new AgentRepository(agents, versions);

        AgentVersionDO existing = new AgentVersionDO();
        existing.setId("av_2");
        existing.setStatus("PUBLISHED");
        when(versions.selectById("av_2")).thenReturn(existing);

        repository.retire("av_2");
        verify(versions).updateById(existing);
    }
}
