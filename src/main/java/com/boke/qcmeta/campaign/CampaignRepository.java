package com.boke.qcmeta.campaign;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.model.StoreModels.NewMessage;
import com.boke.qcmeta.store.MessageRepository;
import com.boke.qcmeta.mapper.CampaignMapper;
import com.boke.qcmeta.model.db.CampaignRows.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Repository
public class CampaignRepository {
    private final CampaignMapper database;
    private final TransactionTemplate transactions;
    private final MessageRepository messages;
    public CampaignRepository(CampaignMapper database, TransactionTemplate transactions, MessageRepository messages) {
        this.database = database; this.transactions = transactions; this.messages = messages;
    }
    public String fingerprint(String businessId, String campaignId) {
        var rows = database.fingerprint(campaignId);
        if (rows.isEmpty()) return null;
        if (!businessId.equals(rows.getFirst().businessId())) throw ApiException.notFound("活动不存在");
        return rows.getFirst().requestFingerprint() == null ? "LEGACY_NO_FINGERPRINT" : rows.getFirst().requestFingerprint();
    }
    public CampaignModels.View create(CampaignModels.CreateRequest request, String fingerprint,
                                      List<NewMessage> rows) {
        return transactions.execute(status -> {
            int count;
            try { count = database.insert(new Create(request.campaignId().trim(), request.businessId().trim(), request.senderId().trim(), text(request.name()), rows.size(), fingerprint, text(request.sourceId()))); }
            catch (org.springframework.dao.DuplicateKeyException duplicate) { count = 0; }
            if (count == 0) {
                if (!fingerprint.equals(fingerprint(request.businessId().trim(), request.campaignId().trim())))
                    throw ApiException.conflict("campaignId 已被不同请求使用");
                return get(request.businessId().trim(), request.campaignId().trim(), true);
            }
            messages.insertCampaignMessages(rows);
            return get(request.businessId().trim(), request.campaignId().trim(), false);
        });
    }
    public CampaignModels.View get(String businessId, String campaignId, boolean duplicate) {
        var rows = database.statistics(businessId, campaignId);
        if (rows.isEmpty()) throw ApiException.notFound("活动不存在");
        return rows.getFirst().view(duplicate);
    }
    public CampaignModels.View cancel(String businessId, String campaignId) {
        return transactions.execute(status -> {
            var rows = database.lock(businessId, campaignId);
            if (rows.isEmpty()) throw ApiException.notFound("活动不存在");
            // 核心仓库负责消息状态和本地取消事件，活动本身只控制待发送部分。
            messages.cancelCampaign(campaignId);
            return get(businessId, campaignId, false);
        });
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
