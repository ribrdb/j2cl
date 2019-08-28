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

import com.google.common.collect.ImmutableSet;
import com.google.j2cl.ast.annotations.Visitable;
import com.google.j2cl.ast.processors.common.Processor;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/** A usage-site reference to a type. */
@Visitable
public abstract class TypeDescriptor extends Node
    implements Comparable<TypeDescriptor>, HasReadableDescription {

  public boolean isJsType() {
    return false;
  }

  public boolean isJsFunctionImplementation() {
    return false;
  }

  public boolean isJsFunctionInterface() {
    return false;
  }

  public boolean isJsEnum() {
    return false;
  }

  /**
   * Returns the correspoinding {@link JsEnumInfo} if the type is a {@link
   * jsinterop.annotations.JsEnum} otherwise {@code null}
   */
  public JsEnumInfo getJsEnumInfo() {
    return null;
  }

  public boolean isNative() {
    return false;
  }

  /** Return whether this type can be used directly by JavaScript code. */
  public abstract boolean canBeReferencedExternally();

  public boolean isPrimitive() {
    return false;
  }

  /** Returns whether the described type is a union. */
  public boolean isUnion() {
    return false;
  }

  /** Returns whether the described type is an array. */
  public boolean isArray() {
    return false;
  }

  /** Returns whether the described type is a class. */
  public boolean isClass() {
    return false;
  }

  /** Returns whether the described type is an interface. */
  public boolean isInterface() {
    return false;
  }

  /** Returns whether the described type is an enum type. */
  public boolean isEnum() {
    return false;
  }

  /** Returns whether the described type is an interface. */
  public boolean isIntersection() {
    return false;
  }

  /** Returns whether the described type is a type variable. */
  public boolean isTypeVariable() {
    return false;
  }

  /** Returns the type that holds the metadata for the class type */
  @Nullable
  public abstract TypeDeclaration getMetadataTypeDeclaration();

  /** Returns the functional interface implemented by the type */
  @Nullable
  public DeclaredTypeDescriptor getFunctionalInterface() {
    return null;
  }

  /** Returns the corresponding primitive type if the {@code setTypeDescriptor} is a boxed type. */
  public PrimitiveTypeDescriptor toUnboxedType() {
    return (PrimitiveTypeDescriptor) this;
  }

  /**
   * Returns the corresponding reference type if the {@code setTypeDescriptor} is a primitive type.
   */
  public DeclaredTypeDescriptor toBoxedType() {
    return (DeclaredTypeDescriptor) this;
  }

  /** Returns the value for uninitialized expression of this type. */
  public Expression getDefaultValue() {
    return NullLiteral.get();
  }

  /** Return whether this type is nullable or not. */
  public abstract boolean isNullable();

  /** Return a nullable version of this type descriptor if possible. */
  public abstract TypeDescriptor toNullable();

  /** Return a non nullable version of this type descriptor if possible. */
  public abstract TypeDescriptor toNonNullable();

  /** Returns type descriptor for the same type use the type parameters from the declaration. */
  public abstract TypeDescriptor toUnparameterizedTypeDescriptor();

  /**
   * Returns the erasure type (see definition of erasure type at
   * http://help.eclipse.org/luna/index.jsp) with an empty type arguments list.
   */
  @Nullable
  public abstract TypeDescriptor toRawTypeDescriptor();

  /**
   * Returns a reference to the JavaScript constructor to be used for array marking, instanceof and
   * casts. In most cases it the underlying JavaScript constructor for the class but not in all
   * (such as native @JsTypes and @JsFunctions).
   */
  public JavaScriptConstructorReference getMetadataConstructorReference() {
    return new JavaScriptConstructorReference(getMetadataTypeDeclaration());
  }

  /** Returns all the free type variables that appear in the type. */
  public Set<TypeVariable> getAllTypeVariables() {
    return ImmutableSet.of();
  }

  /**
   * A mapping that fully describes the final specialized type argument value for every super type
   * or interface of the current type.
   *
   * <p>For example given:
   *
   * <pre>
   * class A<A1, A2> {}
   * class B<B1> extends A<String, B1>
   * class C<C1> extends B<C1>
   * </pre>
   *
   * <p>If the current type is C then the resulting mappings are:
   *
   * <pre>
   * - A1 -> String
   * - A2 -> C1
   * - B1 -> C1
   * </pre>
   */
  public abstract Map<TypeVariable, TypeDescriptor> getSpecializedTypeArgumentByTypeParameters();

  public TypeDescriptor specializeTypeVariables(
      Map<TypeVariable, TypeDescriptor> replacementTypeArgumentByTypeVariable) {
    return specializeTypeVariables(
        TypeDescriptors.mappingFunctionFromMap(replacementTypeArgumentByTypeVariable));
  }

  /** Replaces all occurrences of a type variable for the type specified by the mapping function. */
  public abstract TypeDescriptor specializeTypeVariables(
      Function<TypeVariable, ? extends TypeDescriptor> replacementTypeArgumentByTypeVariable);

  /**
   * Returns true if the two types have the same raw type.
   *
   * <p>The raw type is always an unparameterized (nullable) declared type or a primitive type. And
   * is defined as follows:
   * <li>If the type is a primitive type "{@code p}"-> then its raw type is itself, "{@code p}".
   * <li>If the type is a class, interface or enum "{@code !C<String>}" -> then its raw type is the
   *     (nullable) declared type with no parameterization, "{@code C}"
   * <li>If the type is an array type "{@code A[]}" -> its raw type is an array of the same
   *     dimensions but whose leaf type is the raw type of the leaf type of the original array.
   * <li>if the type is an intersection type "{@code (A<T> & B<U>)}" -> then its raw type is the raw
   *     type of the first component, "{@code A}".
   * <li>if the type is a type variable "{@code <T extends A>}"-> then its raw type is the raw type
   *     of its upper bound.
   * <li>if the types in an union type "{@code (A | B)}" -> then its raw type is the closest common
   *     supertype.
   */
  public final boolean hasSameRawType(TypeDescriptor other) {
    return toRawTypeDescriptor().isSameBaseType(other.toRawTypeDescriptor());
  }

  /**
   * For primitives, classes, interfaces, enums and arrays of those isSameBaseType returns {@code
   * true} if they have the same raw type. For all others it is only {@code true} if both types are
   * the same.
   */
  public boolean isSameBaseType(TypeDescriptor other) {
    return equals(other);
  }

  public boolean isAssignableTo(TypeDescriptor that) {
    return this == that;
  }

  /** A unique string for a give type. Used for interning. */
  public abstract String getUniqueId();

  @Override
  public final int compareTo(TypeDescriptor that) {
    return getUniqueId().compareTo(that.getUniqueId());
  }

  @Override
  public final boolean equals(Object o) {
    if (o == null) {
      return false;
    }

    if (o == this) {
      return true;
    }

    if (o instanceof TypeDescriptor) {
      return getUniqueId().equals(((TypeDescriptor) o).getUniqueId());
    }
    return false;
  }

  @Override
  public final int hashCode() {
    return getUniqueId().hashCode();
  }

  @Override
  public final String toString() {
    return getUniqueId();
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_TypeDescriptor.visit(processor, this);
  }
}
