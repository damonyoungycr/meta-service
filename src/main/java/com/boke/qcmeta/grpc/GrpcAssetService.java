package com.boke.qcmeta.grpc;

import com.boke.qcmeta.grpc.v1.*;
import com.boke.qcmeta.media.AssetService;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class GrpcAssetService extends AssetServiceGrpc.AssetServiceImplBase {
    private final AssetService assets;
    public GrpcAssetService(AssetService assets) { this.assets=assets; }
    @Override public void uploadAsset(UploadAssetRequest request,StreamObserver<Asset> observer) {
        GrpcSupport.respond(observer,()->view(assets.upload(request.getBusinessId(),request.getSenderId(),request.getFilename(),
                request.getMimeType(),request.getContent().toByteArray(),request.getDirectoryType(),request.getSenderPhone())));
    }
    @Override public void getAsset(GetAssetRequest request,StreamObserver<Asset> observer) {
        GrpcSupport.respond(observer,()->view(assets.get(request.getBusinessId(),request.getAssetId())));
    }
    @Override public void retryAsset(GetAssetRequest request,StreamObserver<Asset> observer) {
        GrpcSupport.respond(observer,()->view(assets.retry(request.getBusinessId(),request.getAssetId())));
    }
    @Override public void uploadAssetToYCloud(GetAssetRequest request,StreamObserver<Asset> observer) {
        GrpcSupport.respond(observer,()->view(assets.uploadToYCloud(request.getBusinessId(),request.getAssetId())));
    }
    static Asset view(AssetService.Asset asset) {
        var result=Asset.newBuilder().setAssetId(asset.assetId()).setBusinessId(asset.businessId()).setSenderId(asset.senderId())
                .setStatus(asset.status()).setFilename(asset.filename()).setMimeType(asset.mimeType()).setSizeBytes(asset.sizeBytes())
                .setUrl(asset.url()).setProviderMediaId(asset.providerMediaId()).setInboundMessageId(asset.inboundMessageId())
                .setErrorCode(asset.errorCode()).setCreatedAt(GrpcConverters.timestamp(asset.createdAt()))
                .setUpdatedAt(GrpcConverters.timestamp(asset.updatedAt())).setSenderPhone(asset.senderPhone());
        if(asset.providerExpiresAt()!=null) result.setProviderExpiresAt(GrpcConverters.timestamp(asset.providerExpiresAt()));
        return result.build();
    }
}
