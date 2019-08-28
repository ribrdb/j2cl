/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.generator;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.j2cl.ast.HasName;
import com.google.j2cl.ast.Member;
import com.google.j2cl.ast.Type;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourcePosition;
import com.google.j2cl.generator.ImportGatherer.ImportCategory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A base class for JavaScript source generators. We may have two subclasses, which are
 * for Header and Impl generation.
 */
public abstract class JavaScriptGenerator {
  protected final Type type;
  protected final GenerationEnvironment environment;
  protected final Multimap<ImportCategory, Import> importsByCategory;
  protected final SourceBuilder sourceBuilder = new SourceBuilder();
  protected final Problems problems;
  protected final boolean declareLegacyNamespace;

  public JavaScriptGenerator(Problems problems, boolean declareLegacyNamespace, Type type) {
    this.problems = problems;
    this.declareLegacyNamespace = declareLegacyNamespace;
    this.type = type;
    importsByCategory = ImportGatherer.gatherImports(type, declareLegacyNamespace);
    Collection<Import> imports = importsByCategory.values();
    Map<HasName, String> uniqueNameByVariable =
        UniqueVariableNamesGatherer.computeUniqueVariableNames(imports, type);
    environment = new GenerationEnvironment(imports, uniqueNameByVariable);
  }

  public Map<SourcePosition, SourcePosition> getSourceMappings() {
    return sourceBuilder.getMappings();
  }

  public Map<Member, SourcePosition> getOutputSourceInfoByMember() {
    return sourceBuilder.getOutputSourceInfoByMember();
  }

  abstract String renderOutput();

  abstract String getSuffix();

  /**
   * @param suppressions file level suppresions. Should be only used as a workaround if an urgent
   *     suppression is needed without needing to wait for JsCompiler release.
   */
  void renderSuppressions(String... suppressions) {
    checkArgument(suppressions.length > 0);
    sourceBuilder.appendLines(
        "/** @fileoverview @suppress {" + Joiner.on(", ").join(suppressions) + "} */");
    sourceBuilder.newLine();
  }

  static Iterable<Import> sortImports(Iterable<Import> iterable) {
    List<Import> sortedList = new ArrayList<>();
    Iterables.addAll(sortedList, iterable);
    Collections.sort(sortedList);
    return sortedList;
  }
}
