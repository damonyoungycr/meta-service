package com.boke.qcmeta.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("qc.assets")
public class AssetProperties {
    private String endpoint = "";
    private String accessKeyId = "";
    private String accessKeySecret = "";
    private String bucketName = "";
    private String imgKey = "";
    private String previewImg = "";
    private long maxBytes = 104857600;
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String value) { accessKeyId = value; }
    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String value) { accessKeySecret = value; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String value) { bucketName = value; }
    public String getImgKey() { return imgKey; }
    public void setImgKey(String value) { imgKey = value; }
    public String getPreviewImg() { return previewImg; }
    public void setPreviewImg(String value) { previewImg = value; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long value) { maxBytes = value; }
}
