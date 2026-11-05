package com.tioka.agent.application;

import com.tioka.agent.infrastructure.AgentMarketplaceRepository;
import com.tioka.agent.infrastructure.persistence.AgentMarketplaceDO;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MarketplaceService {
    private final AgentMarketplaceRepository listings;
    public MarketplaceService(AgentMarketplaceRepository listings) { this.listings = listings; }

    public Listing submit(String assetType, String assetId) {
        Listing l = new Listing("lst_" + UUID.randomUUID().toString().substring(0, 8), assetType, assetId, "PENDING");
        AgentMarketplaceDO row = new AgentMarketplaceDO();
        row.setId(l.id()); row.setAssetType(l.assetType()); row.setAssetId(l.assetId()); row.setStatus(l.status());
        listings.insert(row);
        return l;
    }

    public Listing approve(String id) {
        AgentMarketplaceDO row = listings.findById(id);
        row.setStatus("APPROVED");
        listings.update(row);
        return new Listing(row.getId(), row.getAssetType(), row.getAssetId(), row.getStatus());
    }

    public record Listing(String id, String assetType, String assetId, String status) {}
}
