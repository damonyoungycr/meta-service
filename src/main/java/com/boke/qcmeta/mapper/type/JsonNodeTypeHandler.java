package com.boke.qcmeta.mapper.type;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(JsonNode.class)
public final class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, JsonNode value, JdbcType jdbcType)
            throws SQLException {
        statement.setString(index, value.toString());
    }

    @Override public JsonNode getNullableResult(ResultSet result, String column) throws SQLException {
        return parse(result.getString(column));
    }
    @Override public JsonNode getNullableResult(ResultSet result, int column) throws SQLException {
        return parse(result.getString(column));
    }
    @Override public JsonNode getNullableResult(CallableStatement statement, int column) throws SQLException {
        return parse(statement.getString(column));
    }
    private static JsonNode parse(String value) throws SQLException {
        if (value == null || value.isBlank()) return null;
        try { return JSON.readTree(value); }
        catch (JsonProcessingException exception) {
            // JSON 异常可能携带客户正文，不保留原异常和内容。
            throw new SQLException("数据库 JSON 字段格式无效");
        }
    }
}
