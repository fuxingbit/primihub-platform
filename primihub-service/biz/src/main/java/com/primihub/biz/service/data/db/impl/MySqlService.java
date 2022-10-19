package com.primihub.biz.service.data.db.impl;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.ExceptionSorter;
import com.primihub.biz.convert.DataResourceConvert;
import com.primihub.biz.entity.base.BaseResultEntity;
import com.primihub.biz.entity.base.BaseResultEnum;
import com.primihub.biz.entity.data.po.DataFileField;
import com.primihub.biz.entity.data.po.DataSource;
import com.primihub.biz.service.data.DataResourceService;
import com.primihub.biz.service.data.db.DataDBService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MySqlService extends DataDBService {

    private static final String pattern="jdbc:(?<type>[a-z]+)://(?<host>[a-zA-Z0-9-//.]+):(?<port>[0-9]+)/(?<database>[a-zA-Z0-9_//-]+)?";

    protected static final String QUERY_TABLES_SQL = "select table_name as tableName from information_schema.tables where table_schema = ? order by table_name desc";
    protected static final String QUERY_DETAILS_SQL = "select * from <tableName> limit 0,50";
    protected static final String QUERY_COUNT_SQL = "select count(*) total from <tableName>";
    protected static final String QUERY_COUNT_Y_SQL = "select count(*) ytotal from <tableName>";


    @Autowired
    private DataResourceService dataResourceService;

    @Override
    public BaseResultEntity healthConnection(DataSource dbSource) {
        String url = dbSource.getDbUrl();
        String dataBaseName = getDataBaseName(url);
        if (StringUtils.isBlank(dataBaseName))
            return BaseResultEntity.failure(BaseResultEnum.DATA_DB_FAIL,"解析数据库名称失败");
        dbSource.setDbName(dataBaseName);
        return dataSourceTables(dbSource);
    }

    @Override
    public BaseResultEntity dataSourceTables(DataSource dbSource) {
        DruidDataSource jdbcDataSource =null;
        try {
            jdbcDataSource = getJdbcDataSource(dbSource);
            JdbcTemplate jdbcTemplate = getJdbcTemplate(jdbcDataSource);
            List<Map<String, Object>> tableNames = jdbcTemplate.queryForList(QUERY_TABLES_SQL,dbSource.getDbName());
            List<Object> tableNameList = tableNames.stream().map(m -> m.get("tableName")).collect(Collectors.toList());
            Map<String,Object> map = new HashMap<>();
            dbSource.setDbPassword(null);
            map.put("dbSource",dbSource);
            map.put("tableNames",tableNameList);
            return BaseResultEntity.success(map);
        }catch (Exception e){
            log.info("url:{}-------e:{}",dbSource.getDbUrl(),e.getMessage());
            return BaseResultEntity.failure(BaseResultEnum.DATA_DB_FAIL,"连接失败,请检查数据源地址是否正确");
        }finally {
            if (jdbcDataSource != null && jdbcDataSource.isEnable()){
                jdbcDataSource.close();
            }
        }
    }

    @Override
    public BaseResultEntity dataSourceTableDetails(DataSource dbSource) {
        DruidDataSource jdbcDataSource =null;
        try {
            jdbcDataSource = getJdbcDataSource(dbSource);
            JdbcTemplate jdbcTemplate = getJdbcTemplate(jdbcDataSource);
            List<Map<String, Object>> details = jdbcTemplate.queryForList(QUERY_DETAILS_SQL.replace("<tableName>",dbSource.getDbTableName()));
            if (details.isEmpty())
                return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"没有查询到数据信息");
            Set<String> columns = details.get(0).keySet();
            List<DataFileField> dataFileFields = dataResourceService.batchInsertDataDataSourceField(columns, details.get(0));
            Map<String,Object> map = new HashMap<>();
            map.put("fieldList",dataFileFields.stream().map(DataResourceConvert::DataFileFieldPoConvertVo).collect(Collectors.toList()));
            map.put("dataList",details);
            return BaseResultEntity.success(map);
        }catch (Exception e){
            log.info("url:{}-------e:{}",dbSource.getDbUrl(),e.getMessage());
            return BaseResultEntity.failure(BaseResultEnum.DATA_DB_FAIL,"连接失败,请检查数据源地址是否正确");
        }finally {
            if (jdbcDataSource != null && jdbcDataSource.isEnable()){
                jdbcDataSource.close();
            }
        }
    }

    public BaseResultEntity tableDataStatistics(DataSource dataSource,boolean isY){
        Map<String,Object> map = new HashMap<>();
        DruidDataSource jdbcDataSource =null;
        try {
            jdbcDataSource = getJdbcDataSource(dataSource);
            JdbcTemplate jdbcTemplate = getJdbcTemplate(jdbcDataSource);
            Map<String, Object> countMap = jdbcTemplate.queryForMap(QUERY_COUNT_SQL.replace("<tableName>", dataSource.getDbTableName()));
            map.putAll(countMap);
            if (isY){
                Map<String, Object> countYMap = jdbcTemplate.queryForMap(QUERY_COUNT_Y_SQL.replace("<tableName>", dataSource.getDbTableName()));
                map.putAll(countYMap);
            }
            return BaseResultEntity.success(map);
        }catch (Exception e){
            log.info("url:{}-------e:{}",dataSource.getDbUrl(),e.getMessage());
            return BaseResultEntity.failure(BaseResultEnum.DATA_DB_FAIL,"连接失败,请检查数据源地址是否正确");
        }finally {
            if (jdbcDataSource != null && jdbcDataSource.isEnable()){
                jdbcDataSource.close();
            }
        }
    }

    private String getDataBaseName(String url){
        Pattern namePattern = Pattern.compile(pattern);
        Matcher dateMatcher = namePattern.matcher(url);
        String database = null;
        while (dateMatcher.find()) {
            database = dateMatcher.group("database");
        }
        return database;
    }
}