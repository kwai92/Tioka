package com.tioka.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.infrastructure.AutomationJobRepository;
import com.tioka.runtime.infrastructure.persistence.AutomationJobDO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AutomationJobService {
    private final AutomationJobRepository jobs;
    private final ObjectMapper mapper = new ObjectMapper();

    public AutomationJobService(AutomationJobRepository jobs) {
        this.jobs = jobs;
    }

    public Job submit(String type, Map<String, Object> payload) {
        String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
        String json;
        try {
            json = mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            json = "{}";
        }
        Job job = new Job(id, type, "COMPLETED", payload);
        AutomationJobDO row = new AutomationJobDO();
        row.setId(job.id());
        row.setJobType(job.type());
        row.setStatus(job.status());
        row.setPayloadJson(json);
        jobs.insert(row);
        return job;
    }

    public record Job(String id, String type, String status, Map<String, Object> payload) {}
}
