/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.guice;

import com.google.common.base.Preconditions;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.apache.druid.java.util.common.logger.Logger;
import org.skife.config.ConfigurationObjectFactory;

import java.util.Map;

/**
 *
 */
@Deprecated
public class ConfigProvider<T> implements Provider<T>
{
  private static final Logger log = new Logger(ConfigProvider.class);

  public static <T> void bind(Binder binder, Class<T> clazz)
  {
    binder.bind(clazz).toProvider(of(clazz)).in(LazySingleton.class);
  }

  public static <T> Provider<T> of(Class<T> clazz)
  {
    return of(clazz, null);
  }

  public static <T> Provider<T> of(Class<T> clazz, Map<String, String> replacements)
  {
    return new ConfigProvider<>(clazz, replacements);
  }

  private final Class<T> clazz;
  private final Map<String, String> replacements;

  private ConfigurationObjectFactory factory = null;

  public ConfigProvider(
      Class<T> clazz,
      Map<String, String> replacements
  )
  {
    this.clazz = clazz;
    this.replacements = replacements;
  }

  @Inject
  public void inject(ConfigurationObjectFactory factory)
  {
    this.factory = factory;
  }

  @Override
  public T get()
  {
    try {
      // ConfigMagic handles a null replacements
      Preconditions.checkNotNull(factory, "Code misconfigured, inject() didn't get called.");
      return factory.buildWithReplacements(clazz, replacements);
    }
    catch (IllegalArgumentException e) {
      log.info("Unable to build instance of class[%s]", clazz);
      throw e;
    }
  }
}
