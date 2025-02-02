/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.flink.catalog.factories;

import static com.netease.arctic.ams.api.Constants.THRIFT_TABLE_SERVICE_NAME;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.TABLE_FORMATS;
import static com.netease.arctic.flink.catalog.factories.CatalogFactoryOptions.DEFAULT_DATABASE;
import static org.apache.flink.table.factories.FactoryUtil.PROPERTY_VERSION;

import com.netease.arctic.UnifiedCatalog;
import com.netease.arctic.UnifiedCatalogLoader;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.ams.api.client.ArcticThriftUrl;
import com.netease.arctic.flink.catalog.FlinkUnifiedCatalog;
import com.netease.arctic.utils.CatalogUtil;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.Set;

/** Factory for {@link FlinkUnifiedCatalog}. */
public class FlinkUnifiedCatalogFactory implements CatalogFactory {

  public static final Set<TableFormat> SUPPORTED_FORMATS =
      Sets.newHashSet(
          TableFormat.MIXED_ICEBERG,
          TableFormat.MIXED_HIVE,
          TableFormat.ICEBERG,
          TableFormat.PAIMON);

  @Override
  public String factoryIdentifier() {
    return CatalogFactoryOptions.UNIFIED_IDENTIFIER;
  }

  @Override
  public Set<ConfigOption<?>> requiredOptions() {
    Set<ConfigOption<?>> requiredOptions = new HashSet<>();
    requiredOptions.add(CatalogFactoryOptions.METASTORE_URL);
    return requiredOptions;
  }

  @Override
  public Set<ConfigOption<?>> optionalOptions() {
    final Set<ConfigOption<?>> options = new HashSet<>();
    options.add(PROPERTY_VERSION);
    options.add(DEFAULT_DATABASE);
    return options;
  }

  @Override
  public Catalog createCatalog(Context context) {
    final FactoryUtil.CatalogFactoryHelper helper =
        FactoryUtil.createCatalogFactoryHelper(this, context);
    helper.validate();

    final String defaultDatabase = helper.getOptions().get(DEFAULT_DATABASE);
    String metastoreUrl = helper.getOptions().get(CatalogFactoryOptions.METASTORE_URL);

    String amoroCatalogName =
        ArcticThriftUrl.parse(metastoreUrl, THRIFT_TABLE_SERVICE_NAME).catalogName();
    UnifiedCatalog unifiedCatalog =
        UnifiedCatalogLoader.loadUnifiedCatalog(metastoreUrl, amoroCatalogName, Maps.newHashMap());
    Configuration hadoopConf = unifiedCatalog.authenticationContext().getConfiguration();

    Set<TableFormat> tableFormats =
        CatalogUtil.tableFormats(unifiedCatalog.metastoreType(), unifiedCatalog.properties());
    validate(tableFormats);

    return new FlinkUnifiedCatalog(
        metastoreUrl, defaultDatabase, unifiedCatalog, context, hadoopConf);
  }

  private void validate(Set<TableFormat> expectedFormats) {
    if (expectedFormats.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "The table formats must be specified in the catalog properties: [%s]",
              TABLE_FORMATS));
    }
    if (!SUPPORTED_FORMATS.containsAll(expectedFormats)) {
      throw new IllegalArgumentException(
          String.format(
              "The table formats [%s] are not supported in the unified catalog, the supported table formats are [%s].",
              expectedFormats, SUPPORTED_FORMATS));
    }
  }
}
