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
package com.google.j2cl.ast;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.j2cl.ast.annotations.Visitable;
import com.google.j2cl.ast.processors.common.Processor;
import com.google.j2cl.common.SourcePosition;
import java.util.Optional;

/** Class for local variable and parameter. */
@Visitable
public class Variable extends Node
    implements Cloneable<Variable>, HasUnusableByJsSuppression, HasName {
  private final String name;
  @Visitable TypeDescriptor typeDescriptor;
  private final boolean isFinal;
  private final boolean isParameter;
  private final Optional<SourcePosition> sourcePosition;
  private final boolean isUnusableByJsSuppressed;

  private Variable(
      Optional<SourcePosition> sourcePosition,
      String name,
      TypeDescriptor typeDescriptor,
      boolean isFinal,
      boolean isParameter,
      boolean isUnusableByJsSuppressed) {
    this.name = checkNotNull(name);
    setTypeDescriptor(typeDescriptor);
    this.isFinal = isFinal;
    this.isParameter = isParameter;
    this.sourcePosition = sourcePosition;
    this.isUnusableByJsSuppressed = isUnusableByJsSuppressed;
  }

  public String getName() {
    return name;
  }

  public TypeDescriptor getTypeDescriptor() {
    return typeDescriptor;
  }

  public boolean isFinal() {
    return isFinal;
  }

  public boolean isParameter() {
    return isParameter;
  }

  public void setTypeDescriptor(TypeDescriptor typeDescriptor) {
    this.typeDescriptor = checkNotNull(typeDescriptor);
  }

  @Override
  public boolean isUnusableByJsSuppressed() {
    return isUnusableByJsSuppressed;
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_Variable.visit(processor, this);
  }

  public VariableReference getReference() {
    return new VariableReference(this);
  }

  @Override
  public Variable clone() {
    return Variable.Builder.from(this).build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public Optional<SourcePosition> getSourcePosition() {
    return checkNotNull(sourcePosition);
  }

  /** Builder for Variable. */
  public static class Builder {

    private String name;
    private TypeDescriptor typeDescriptor;
    private boolean isFinal;
    private boolean isParameter;
    private Optional<SourcePosition> sourcePosition = Optional.empty();
    private boolean isUnusableByJsSuppressed = false;

    public static Builder from(Variable variable) {
      Builder builder = new Builder();
      builder.name = variable.getName();
      builder.typeDescriptor = variable.getTypeDescriptor();
      builder.isFinal = variable.isFinal();
      builder.isParameter = variable.isParameter;
      builder.isUnusableByJsSuppressed = variable.isUnusableByJsSuppressed;
      builder.sourcePosition = variable.sourcePosition;
      return builder;
    }

    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    public Builder setTypeDescriptor(TypeDescriptor typeDescriptor) {
      this.typeDescriptor = typeDescriptor;
      return this;
    }

    public Builder setParameter(boolean isParameter) {
      this.isParameter = isParameter;
      return this;
    }

    public Builder setFinal(boolean isFinal) {
      this.isFinal = isFinal;
      return this;
    }

    public Builder setSourcePosition(SourcePosition sourcePosition) {
      this.sourcePosition = Optional.of(sourcePosition);
      return this;
    }

    public Builder setUnusableByJsSuppressed(boolean isUnusableByJsSuppressed) {
      this.isUnusableByJsSuppressed = isUnusableByJsSuppressed;
      return this;
    }

    public Variable build() {
      checkState(name != null);
      checkState(typeDescriptor != null);
      return new Variable(
          sourcePosition,
          name,
          typeDescriptor,
          isFinal,
          isParameter,
          isUnusableByJsSuppressed);
    }
  }
}
