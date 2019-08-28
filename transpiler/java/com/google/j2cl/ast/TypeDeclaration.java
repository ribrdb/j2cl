/*
 * Copyright 2017 Google Inc.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.j2cl.ast.TypeDescriptors.BootstrapType;
import com.google.j2cl.ast.annotations.Visitable;
import com.google.j2cl.ast.processors.common.Processor;
import com.google.j2cl.common.ThreadLocalInterner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A declaration-site reference to a type.
 *
 * <p>This class is mostly a bag of precomputed properties, and the details of how those properties
 * are created live in several creation functions in JdtUtils and TypeDeclarations.
 *
 * <p>A couple of properties are lazily calculated via the DescriptorFactory and interface, since
 * eagerly calculating them would lead to infinite loops of Descriptor creation.
 *
 * <p>Since these are all declaration-site references, when there are type variables they are always
 * thought of as type parameters.
 */
@AutoValue
@Visitable
public abstract class TypeDeclaration extends Node
    implements HasJsNameInfo, HasReadableDescription, HasUnusableByJsSuppression {
  /**
   * References to some descriptors need to be deferred in some cases since it will cause infinite
   * loops.
   */
  public interface DescriptorFactory<T> {
    T get(TypeDeclaration typeDeclaration);
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_TypeDeclaration.visit(processor, this);
  }

  @Override
  public final boolean equals(Object o) {
    if (o instanceof TypeDeclaration) {
      return getUniqueId().equals(((TypeDeclaration) o).getUniqueId());
    }
    return false;
  }

  @Memoized
  public boolean declaresDefaultMethods() {
    return isInterface()
        && getDeclaredMethodDescriptors().stream().anyMatch(MethodDescriptor::isDefaultMethod);
  }

  /** Returns the unqualified simple source name like "Inner". */
  @Memoized
  public String getSimpleSourceName() {
    return AstUtils.getSimpleSourceName(getClassComponents());
  }

  /** Returns the simple binary name like "Outer$Inner". Used for file naming purposes. */
  @Memoized
  public String getSimpleBinaryName() {
    return Joiner.on('$').join(getClassComponents());
  }

  /**
   * Returns the fully package qualified binary name like "com.google.common.Outer$Inner".
   *
   * <p>Used for generated class metadata (per JLS), file overview, file path, unique id calculation
   * and other similar scenarios.
   */
  @Memoized
  public String getQualifiedBinaryName() {
    return Joiner.on(".").skipNulls().join(getPackageName(), getSimpleBinaryName());
  }

  /** Returns the globally unique qualified name by which this type should be defined/imported. */
  public String getModuleName() {
    return getQualifiedJsName();
  }

  /** Returns the type descriptor for the module that needs to be required for this type */
  @Memoized
  public TypeDeclaration getEnclosingModule() {
    String moduleRelativeJsName = getModuleRelativeJsName();
    if (!isNative() || !moduleRelativeJsName.contains(".")) {
      return this;
    }

    if (getEnclosingTypeDeclaration() != null && !hasCustomizedJsNamespace()) {
      // Make sure that if the enclosing module is a non native type, getEnclosing module returns
      // the normal Java TypeDeclaration instead of synthesizing a native one. This is important
      // because it guarantees that the type will be goog.required using the "$impl" module not the
      // header module which might cause dependency cycles.
      return getEnclosingTypeDeclaration().getEnclosingModule();
    }

    // Synthesize a module root.
    String enclosingJsName = Iterables.get(Splitter.on('.').split(moduleRelativeJsName), 0);
    String enclosingJsNamespace = getJsNamespace();
    return TypeDescriptors.createNativeTypeDescriptor(enclosingJsNamespace, enclosingJsName)
        .getTypeDeclaration();
  }

  /**
   * Returns the qualifier for the type from the root of the module, @{code ""} if the type is the
   * module root.
   */
  @Memoized
  public String getInnerTypeQualifier() {
    String moduleRelativeJsName = getModuleRelativeJsName();
    int dotIndex = moduleRelativeJsName.indexOf('.');
    if (dotIndex == -1) {
      return "";
    }

    return moduleRelativeJsName.substring(dotIndex + 1);
  }

  private boolean hasCustomizedJsNamespace() {
    return getCustomizedJsNamespace() != null;
  }

  public String getImplModuleName() {
    return isNative() || isExtern() ? getModuleName() : getModuleName() + "$impl";
  }

  /** Returns the fully package qualified name like "com.google.common". */
  @Nullable
  public abstract String getPackageName();

  /**
   * Returns a list of Strings representing the current type's simple name and enclosing type simple
   * names. For example for "com.google.foo.Outer" the class components are ["Outer"] and for
   * "com.google.foo.Outer.Inner" the class components are ["Outer", "Inner"].
   */
  public abstract ImmutableList<String> getClassComponents();

  @Nullable
  public abstract TypeDeclaration getEnclosingTypeDeclaration();

  public abstract ImmutableList<TypeVariable> getTypeParameterDescriptors();

  public abstract Visibility getVisibility();

  public abstract Kind getKind();

  public boolean isAbstract() {
    return getHasAbstractModifier()
        || TypeDescriptors.isBoxedTypeAsJsPrimitives(toRawTypeDescriptor());
  }

  public abstract boolean isFinal();

  public abstract boolean isFunctionalInterface();

  @Memoized
  public boolean isJsFunctionImplementation() {
    return isClass()
        && getInterfaceTypeDescriptors().stream().anyMatch(TypeDescriptor::isJsFunctionInterface);
  }

  public abstract boolean isJsFunctionInterface();

  public abstract boolean isJsType();

  /**
   * Returns whether the described type is a nested type (i.e. it is defined inside the body of some
   * enclosing type) but is not a member type because it's location in the body is not in the
   * declaration scope of the enclosing type. For example:
   *
   * <p><code> class Foo { void bar() { class Baz {} } } </code>
   *
   * <p>or
   *
   * <p><code> class Foo { void bar() { Comparable comparable = new Comparable() { ... } } } </code>
   */
  public abstract boolean isLocal();

  public abstract boolean isAnonymous();

  @Override
  public abstract boolean isNative();

  @Nullable
  public abstract JsEnumInfo getJsEnumInfo();

  public abstract boolean isDeprecated();

  public boolean isJsEnum() {
    return getJsEnumInfo() != null;
  }

  @Memoized
  public boolean extendsNativeClass() {
    DeclaredTypeDescriptor superTypeDescriptor = getSuperTypeDescriptor();
    if (superTypeDescriptor == null) {
      return false;
    }

    return superTypeDescriptor.isNative() || superTypeDescriptor.extendsNativeClass();
  }

  public boolean hasJsConstructor() {
    return !getJsConstructorMethodDescriptors().isEmpty();
  }

  public boolean isJsConstructorSubtype() {
    DeclaredTypeDescriptor superTypeDescriptor = getSuperTypeDescriptor();
    return superTypeDescriptor != null && superTypeDescriptor.hasJsConstructor();
  }

  /**
   * Returns the JavaScript name for this class. This is same as simple source name unless modified
   * by JsType.
   */
  @Override
  @Nullable
  public abstract String getSimpleJsName();

  /**
   * Returns the qualifier for the type from the root of the module including the module root.
   *
   * <p>For example in the following code:
   *
   * <pre>
   * <code>
   *   class Top {
   *     @JsType(isNative = true, namespace = "foo", name = "Top.Inner")
   *     class TopInner {
   *       @JsType(isNative = true)
   *       class InnerInner {}
   *     }
   *   }
   *
   * </code>
   * </pre>
   *
   * <p>The module relative JS names are in order is Top, Top.Inner, Top.Inner.InnerInner.
   */
  @Memoized
  String getModuleRelativeJsName() {
    if (!isNative() || hasCustomizedJsNamespace() || getEnclosingTypeDeclaration() == null) {
      return getSimpleJsName();
    }

    String enclosingModuleRelativeName = getEnclosingTypeDeclaration().getModuleRelativeJsName();
    // enclosingModuleRelativeName can only be empty if the type has TypeDescriptors.globalNamespace
    // as an enclosing type. This could only potentially happen in synthetic type descriptors.
    return enclosingModuleRelativeName.isEmpty()
        ? getSimpleJsName()
        : enclosingModuleRelativeName + "." + getSimpleJsName();
  }

  @Override
  @Nullable
  @Memoized
  public String getJsNamespace() {
    if (hasCustomizedJsNamespace()) {
      return getCustomizedJsNamespace();
    }

    if (getEnclosingTypeDeclaration() == null) {
      return getPackageName();
    }

    if (isNative()) {
      return getEnclosingTypeDeclaration().getJsNamespace();
    }

    if (getEnclosingTypeDeclaration().isNative()) {
      // When there is a type nested within a native type, it's important not to generate a name
      // like "Array.1" (like would happen if the outer native type was claiming to be native
      // Array and the nested type was anonymous) since this is almost guaranteed to collide
      // with other people also creating nested classes within a native type that claims to be
      // native Array.
      return getEnclosingTypeDeclaration().getQualifiedSourceName();
    }
    // Use the parent qualified name.
    return getEnclosingTypeDeclaration().getQualifiedJsName();
  }

  @Override
  @Memoized
  public String getQualifiedJsName() {
    if (JsUtils.isGlobal(getJsNamespace())) {
      return getModuleRelativeJsName();
    }
    return getJsNamespace() + "." + getModuleRelativeJsName();
  }

  @Nullable
  abstract String getCustomizedJsNamespace();

  /** Returns true if the class captures its enclosing instance */
  public abstract boolean isCapturingEnclosingInstance();

  public boolean hasTypeParameters() {
    return !getTypeParameterDescriptors().isEmpty();
  }

  /** Returns whether the described type is a class. */
  public boolean isClass() {
    return getKind() == Kind.CLASS;
  }

  /** Returns whether the described type is an interface. */
  public boolean isInterface() {
    return getKind() == Kind.INTERFACE;
  }

  /** Returns whether the described type is an enum. */
  public boolean isEnum() {
    return getKind() == Kind.ENUM;
  }

  @Memoized
  public boolean isExtern() {
    return isNative() && hasExternNamespace();
  }

  private boolean hasExternNamespace() {
    // A native type descriptor is an extern if its namespace is the global namespace or if
    // it inherited the namespace from its (enclosing) extern type.
    return JsUtils.isGlobal(getJsNamespace())
        || (!hasCustomizedJsNamespace()
            && getEnclosingTypeDeclaration() != null
            && getEnclosingTypeDeclaration().isExtern());
  }

  public boolean isStarOrUnknown() {
    return getSimpleJsName().equals("*") || getSimpleJsName().equals("?");
  }

  @Memoized
  public TypeDeclaration getMetadataTypeDeclaration() {
    DeclaredTypeDescriptor rawTypeDescriptor = toRawTypeDescriptor();

    if (rawTypeDescriptor.isNative() || rawTypeDescriptor.isJsEnum()) {
      return getOverlayImplementationTypeDeclaration()
          .toUnparameterizedTypeDescriptor()
          .getTypeDeclaration();
    }

    if (rawTypeDescriptor.isJsFunctionInterface()) {
      return BootstrapType.JAVA_SCRIPT_FUNCTION.getDeclaration();
    }

    return rawTypeDescriptor.getTypeDeclaration();
  }

  @Memoized
  public TypeDeclaration getOverlayImplementationTypeDeclaration() {
    return TypeDescriptors.createOverlayImplementationTypeDeclaration(
        toUnparameterizedTypeDescriptor());
  }

  @Memoized
  public boolean hasOverlayImplementationType() {
    // TODO(b/116825224): this should just be
    //           isNative() || isJsFunctionInteface() && declaresJsOverlayMembers.
    // but there are some synthetic type descriptors created by
    // TypeDescriptors.createNativeGlobalTypeDescriptor that do are marked native and confuse the
    // rewriting of overlay references.
    return ((isJsType() || isJsEnum()) && isNative())
        || (isJsFunctionInterface() && declaresJsOverlayMembers());
  }

  private boolean declaresJsOverlayMembers() {
    return getDeclaredMethodDescriptors().stream().anyMatch(MethodDescriptor::isJsOverlay)
        || getDeclaredFieldDescriptors().stream().anyMatch(FieldDescriptor::isJsOverlay);
  }

  /**
   * Returns a list of the type descriptors of interfaces that are explicitly implemented directly
   * on this type.
   */
  @Memoized
  public ImmutableList<DeclaredTypeDescriptor> getInterfaceTypeDescriptors() {
    return getInterfaceTypeDescriptorsFactory().get(this);
  }

  /** Returns the height of the largest inheritance chain of any interface implemented here. */
  @Memoized
  public int getMaxInterfaceDepth() {
    return 1
        + getInterfaceTypeDescriptors()
            .stream()
            .mapToInt(i -> i.getTypeDeclaration().getMaxInterfaceDepth())
            .max()
            .orElse(0);
  }

  /**
   * Returns a set of the type descriptors of interfaces that are explicitly implemented either
   * directly on this type or on some super type or super interface.
   */
  @Memoized
  public Set<DeclaredTypeDescriptor> getTransitiveInterfaceTypeDescriptors() {
    Set<DeclaredTypeDescriptor> typeDescriptors = new LinkedHashSet<>();

    // Recursively gather from super interfaces.
    for (DeclaredTypeDescriptor interfaceTypeDescriptor : getInterfaceTypeDescriptors()) {
      typeDescriptors.add(interfaceTypeDescriptor);
      typeDescriptors.addAll(interfaceTypeDescriptor.getTransitiveInterfaceTypeDescriptors());
    }

    // Recursively gather from super type.
    DeclaredTypeDescriptor superTypeDescriptor = getSuperTypeDescriptor();
    if (superTypeDescriptor != null) {
      typeDescriptors.addAll(superTypeDescriptor.getTransitiveInterfaceTypeDescriptors());
    }

    return typeDescriptors;
  }

  /**
   * Returns the erasure type (see definition of erasure type at
   * http://help.eclipse.org/luna/index.jsp) with an empty type arguments list.
   */
  @Memoized
  public DeclaredTypeDescriptor toRawTypeDescriptor() {
    return DeclaredTypeDescriptor.newBuilder()
        .setTypeDeclaration(this)
        .setEnclosingTypeDescriptor(
            applyOrNull(getEnclosingTypeDeclaration(), t -> t.toRawTypeDescriptor()))
        .setSuperTypeDescriptorFactory(
            () -> applyOrNull(getSuperTypeDescriptor(), t -> t.toRawTypeDescriptor()))
        .setInterfaceTypeDescriptorsFactory(
            () ->
                getInterfaceTypeDescriptors().stream()
                    .map(DeclaredTypeDescriptor::toRawTypeDescriptor)
                    .collect(ImmutableList.toImmutableList()))
        .setTypeArgumentDescriptors(ImmutableList.of())
        .setDeclaredFieldDescriptorsFactory(
            () ->
                getDeclaredFieldDescriptors().stream()
                    .map(FieldDescriptor::toRawMemberDescriptor)
                    .collect(toImmutableList()))
        .setDeclaredMethodDescriptorsFactory(
            () -> {
              ImmutableMap.Builder<String, MethodDescriptor>
                  declaredMethodDescriptorsBySignatureBuilder = ImmutableMap.builder();
              for (MethodDescriptor methodDescriptor : getDeclaredMethodDescriptors()) {
                declaredMethodDescriptorsBySignatureBuilder.put(
                    methodDescriptor.getDeclarationDescriptor().getMethodSignature(),
                    methodDescriptor.toRawMemberDescriptor());
              }
              return declaredMethodDescriptorsBySignatureBuilder.build();
            })
        .setJsFunctionMethodDescriptorFactory(
            () ->
                applyOrNull(
                    toUnparameterizedTypeDescriptor().getJsFunctionMethodDescriptor(),
                    t -> t.toRawMemberDescriptor()))
        .setSingleAbstractMethodDescriptorFactory(
            () ->
                applyOrNull(
                    toUnparameterizedTypeDescriptor().getSingleAbstractMethodDescriptor(),
                    t -> t.toRawMemberDescriptor()))
        .build();
  }

  /**
   * Returns the fully package qualified source name like "com.google.common.Outer.Inner". Used in
   * places where original name is useful (like aliasing, identifying the corressponding java type,
   * Debug/Error output, etc.
   */
  @Memoized
  public String getQualifiedSourceName() {
    return Joiner.on(".")
        .skipNulls()
        .join(getPackageName(), Joiner.on(".").join(getClassComponents()));
  }

  @Memoized
  public @Nullable DeclaredTypeDescriptor getSuperTypeDescriptor() {
    return getSuperTypeDescriptorFactory().get(this);
  }

  /**
   * Returns the usage site TypeDescriptor corresponding to this declaration site TypeDeclaration.
   *
   * <p>A completely correct solution would specialize type parameters into type arguments and
   * cascade those changes into declared methods and modifications to the method declaration site of
   * declared methods. But our AST is not in a position to do all of that. Instead we trust that a
   * real JDT usage site TypeBinding has already been processed somewhere and we attempt to retrieve
   * the matching TypeDescriptor.
   */
  @Memoized
  public DeclaredTypeDescriptor toUnparameterizedTypeDescriptor() {
    return getUnparameterizedTypeDescriptorFactory().get(this);
  }

  /** A unique string for a give type. Used for interning. */
  @Memoized
  public String getUniqueId() {
    String uniqueKey = getQualifiedBinaryName();
    return uniqueKey + TypeDeclaration.createTypeParametersUniqueId(getTypeParameterDescriptors());
  }

  private static String createTypeParametersUniqueId(List<TypeVariable> typeParameterDescriptors) {
    if (typeParameterDescriptors == null || typeParameterDescriptors.isEmpty()) {
      return "";
    }
    return typeParameterDescriptors
        .stream()
        .map(TypeVariable::getUniqueId)
        .collect(joining(", ", "<", ">"));
  }

  @Override
  @Memoized
  public int hashCode() {
    return getUniqueId().hashCode();
  }

  /**
   * The list of methods declared in the type from the JDT. Note: this does not include methods we
   * synthesize and add to the type like bridge methods.
   */
  @Memoized
  Map<String, MethodDescriptor> getDeclaredMethodDescriptorsBySignature() {
    return getDeclaredMethodDescriptorsFactory().get(this);
  }

  /**
   * The list of methods in the type from the JDT. Note: this does not include methods we synthesize
   * and add to the type like bridge methods.
   */
  @Memoized
  Map<String, MethodDescriptor> getMethodDescriptorsBySignature() {
    // TODO(rluble): update this code to handle package private methods, bridges and verify that it
    // correctly handles default methods.
    Map<String, MethodDescriptor> methodDescriptorsBySignature = new LinkedHashMap<>();

    // Add all methods declared in the current type itself
    methodDescriptorsBySignature.putAll(getDeclaredMethodDescriptorsBySignature());

    // Add all the methods from the super class.
    if (getSuperTypeDescriptor() != null) {
      AstUtils.updateMethodsBySignature(
          methodDescriptorsBySignature, getSuperTypeDescriptor().getMethodDescriptors());
    }

    // Finally add the methods that appear in super interfaces.
    for (DeclaredTypeDescriptor implementedInterface : getInterfaceTypeDescriptors()) {
      AstUtils.updateMethodsBySignature(
          methodDescriptorsBySignature, implementedInterface.getMethodDescriptors());
    }
    return methodDescriptorsBySignature;
  }

  /**
   * Returns true if {@code TypeDescriptor} declares a method with the same signature as {@code
   * methodDescriptor} in its body.
   */
  private boolean isOverriddenHere(MethodDescriptor methodDescriptor) {
    for (MethodDescriptor declaredMethodDescriptor : getDeclaredMethodDescriptors()) {
      if (methodDescriptor.isOverride(declaredMethodDescriptor)) {
        return true;
      }
    }
    return false;
  }

  /** Returns {@code true} if {@code this} is subtype of {@code that}. */
  public boolean isSubtypeOf(TypeDeclaration that) {
    // TODO(b/70951075): distinguish between Java isSubtypeOf and our target interpretation of
    // isSubtypeOf for optimization purposes in the context of jsinterop. Note that this method is
    // used assuming it provides Java semantics.
    return TypeDescriptors.isJavaLangObject(that.toUnparameterizedTypeDescriptor())
        || getAllSuperTypesIncludingSelf().contains(that);
  }

  @Memoized
  protected Set<TypeDeclaration> getAllSuperTypesIncludingSelf() {
    Set<TypeDeclaration> allSupertypesIncludingSelf = new LinkedHashSet<>();
    allSupertypesIncludingSelf.add(this);
    if (getSuperTypeDescriptor() != null) {
      allSupertypesIncludingSelf.addAll(
          getSuperTypeDescriptor().getTypeDeclaration().getAllSuperTypesIncludingSelf());
    }
    for (DeclaredTypeDescriptor interfaceTypeDescriptor : getInterfaceTypeDescriptors()) {
      allSupertypesIncludingSelf.addAll(
          interfaceTypeDescriptor.getTypeDeclaration().getAllSuperTypesIncludingSelf());
    }
    return allSupertypesIncludingSelf;
  }

  /**
   * The list of methods declared in the type. Note: this does not include methods synthetic methods
   * (like bridge methods) nor supertype methods that are not overridden in the type.
   */
  @Memoized
  public Collection<MethodDescriptor> getDeclaredMethodDescriptors() {
    return getDeclaredMethodDescriptorsBySignature().values();
  }

  /** Returns the JsConstructor for this class if any. */
  @Memoized
  @Nullable
  public List<MethodDescriptor> getJsConstructorMethodDescriptors() {
    return getDeclaredMethodDescriptors()
        .stream()
        .filter(MethodDescriptor::isJsConstructor)
        .collect(ImmutableList.toImmutableList());
  }
  /**
   * The list of fields declared in the type. Note: this does not include methods synthetic fields
   * (like captures) nor supertype fields.
   */
  @Memoized
  public Collection<FieldDescriptor> getDeclaredFieldDescriptors() {
    return getDeclaredFieldDescriptorsFactory().get(this);
  }

  /** The list of all methods available on a given type. */
  public Collection<MethodDescriptor> getMethodDescriptors() {
    return getMethodDescriptorsBySignature().values();
  }

  /** Returns the default (parameterless) constructor for the type.. */
  @Memoized
  public MethodDescriptor getDefaultConstructorMethodDescriptor() {
    return getDeclaredMethodDescriptors()
        .stream()
        .filter(MethodDescriptor::isConstructor)
        .filter(methodDescriptor -> methodDescriptor.getParameterTypeDescriptors().isEmpty())
        .findFirst()
        .orElse(null);
  }

  /**
   * Returns the method descriptors in this type's interfaces that are accidentally overridden.
   *
   * <p>'Accidentally overridden' means the type itself does not have its own declared overriding
   * method and the method it inherits does not really override, but just has the same signature as
   * the overridden method.
   */
  @Memoized
  public List<MethodDescriptor> getAccidentallyOverriddenMethodDescriptors() {
    List<MethodDescriptor> accidentalOverriddenMethods = new ArrayList<>();

    Set<DeclaredTypeDescriptor> transitiveSuperTypeInterfaceTypeDescriptors =
        getSuperTypeDescriptor() != null
            ? getSuperTypeDescriptor().getTransitiveInterfaceTypeDescriptors()
            : ImmutableSet.of();
    for (DeclaredTypeDescriptor superInterfaceTypeDescriptor :
        Sets.difference(
            getTransitiveInterfaceTypeDescriptors(), transitiveSuperTypeInterfaceTypeDescriptors)) {
      accidentalOverriddenMethods.addAll(
          getNotOverriddenMethodDescriptors(superInterfaceTypeDescriptor));
    }

    return accidentalOverriddenMethods;
  }

  /**
   * Builds and caches a mapping from method override signature to matching method descriptors from
   * the entire super-type hierarchy. This map can *greatly* speed up method override checks.
   */
  @Memoized
  Multimap<String, MethodDescriptor> getMethodDescriptorsByOverrideSignature() {
    Multimap<String, MethodDescriptor> methodDescriptorsByOverrideSignature =
        LinkedHashMultimap.create();

    for (MethodDescriptor declaredMethodDescriptor : getDeclaredMethodDescriptors()) {
      if (declaredMethodDescriptor.isConstructor() || declaredMethodDescriptor.isStatic()) {
        continue;
      }

      methodDescriptorsByOverrideSignature.put(
          declaredMethodDescriptor.getOverrideSignature(), declaredMethodDescriptor);
    }

    // Recurse into immediate super class and interfaces for overridden method.
    if (getSuperTypeDescriptor() != null) {
      methodDescriptorsByOverrideSignature.putAll(
          getSuperTypeDescriptor().getTypeDeclaration().getMethodDescriptorsByOverrideSignature());
    }

    for (DeclaredTypeDescriptor interfaceTypeDescriptor : getInterfaceTypeDescriptors()) {
      methodDescriptorsByOverrideSignature.putAll(
          interfaceTypeDescriptor.getTypeDeclaration().getMethodDescriptorsByOverrideSignature());
    }

    return methodDescriptorsByOverrideSignature;
  }

  /**
   * Returns a set of the method descriptors of methods in this type's super hierarchy that are
   * overridden by {@code methodDescriptor}.
   */
  public Set<MethodDescriptor> getOverriddenMethodDescriptors(MethodDescriptor methodDescriptor) {
    Set<MethodDescriptor> overriddenMethodDescriptors =
        (Set<MethodDescriptor>)
            getMethodDescriptorsByOverrideSignature().get(methodDescriptor.getOverrideSignature());

    Set<MethodDescriptor> overriddenMethodDescriptorsExceptSelf = new LinkedHashSet<>();
    overriddenMethodDescriptorsExceptSelf.addAll(overriddenMethodDescriptors);
    overriddenMethodDescriptorsExceptSelf.remove(methodDescriptor);
    return overriddenMethodDescriptorsExceptSelf;
  }

  /** Returns the method descriptors that are declared in a particular super type but not here. */
  private List<MethodDescriptor> getNotOverriddenMethodDescriptors(
      DeclaredTypeDescriptor superTypeDescriptor) {
    return superTypeDescriptor
        .getDeclaredMethodDescriptors()
        .stream()
        .filter(methodDescriptor -> !isOverriddenHere(methodDescriptor))
        .collect(toImmutableList());
  }

  /**
   * Returns the method descriptor of the nearest method in this type's super classes that overrides
   * (regularly or accidentally) {@code methodDescriptor}.
   */
  public MethodDescriptor getOverridingMethodDescriptorInSuperclasses(
      MethodDescriptor methodDescriptor) {
    DeclaredTypeDescriptor superTypeDescriptor = getSuperTypeDescriptor();
    while (superTypeDescriptor != null) {
      for (MethodDescriptor superMethodDescriptor :
          superTypeDescriptor.getDeclaredMethodDescriptors()) {
        if (superMethodDescriptor.isConstructor() || superMethodDescriptor.isStatic()) {
          continue;
        }

        // TODO(stalcup): exclude package private method, and add a test for it.
        if (superMethodDescriptor.isOverride(methodDescriptor)) {
          return superMethodDescriptor;
        }
      }
      superTypeDescriptor = superTypeDescriptor.getSuperTypeDescriptor();
    }
    return null;
  }

  @Override
  public final String toString() {
    return getUniqueId();
  }

  /** Returns a description that is useful for error messages. */
  @Override
  public String getReadableDescription() {
    // TODO(rluble): Actually provide a real readable description.
    if (isAnonymous()) {
      if (getInterfaceTypeDescriptors().isEmpty()) {
        return "<anonymous> extends " + getSuperTypeDescriptor().getReadableDescription();
      } else {
        return "<anonymous> implements "
            + getInterfaceTypeDescriptors().get(0).getReadableDescription();
      }
    } else if (isLocal()) {
      return getSimpleSourceName().replaceFirst("\\$\\d+", "");
    }
    return getSimpleSourceName();
  }

  /* PRIVATE AUTO_VALUE PROPERTIES */

  abstract boolean getHasAbstractModifier();

  @Nullable
  abstract DescriptorFactory<ImmutableList<DeclaredTypeDescriptor>>
      getInterfaceTypeDescriptorsFactory();

  abstract DescriptorFactory<DeclaredTypeDescriptor> getUnparameterizedTypeDescriptorFactory();

  @Nullable
  abstract DescriptorFactory<DeclaredTypeDescriptor> getSuperTypeDescriptorFactory();

  @Nullable
  abstract DescriptorFactory<ImmutableMap<String, MethodDescriptor>>
      getDeclaredMethodDescriptorsFactory();

  @Nullable
  abstract DescriptorFactory<ImmutableList<FieldDescriptor>> getDeclaredFieldDescriptorsFactory();

  abstract Builder toBuilder();

  public static Builder newBuilder() {
    DescriptorFactory<DeclaredTypeDescriptor> unparameterizedFactory =
        // TODO(b/117105240): Remove once the type declaration is properly decoupled from the
        // type descriptor. For now we need to set all the fields that might be parameterized to
        // provide a reasonable default for the unparameterized type descriptor factory.
        self ->
            DeclaredTypeDescriptor.newBuilder()
                .setTypeDeclaration(self)
                .setEnclosingTypeDescriptor(
                    applyOrNull(
                        self.getEnclosingTypeDeclaration(),
                        t -> t.toUnparameterizedTypeDescriptor()))
                .setSuperTypeDescriptorFactory(() -> self.getSuperTypeDescriptor())
                .setInterfaceTypeDescriptorsFactory(() -> self.getInterfaceTypeDescriptors())
                .setTypeArgumentDescriptors(self.getTypeParameterDescriptors())
                .build();

    return new AutoValue_TypeDeclaration.Builder()
        // Default values.
        .setVisibility(Visibility.PUBLIC)
        .setHasAbstractModifier(false)
        .setAnonymous(false)
        .setNative(false)
        .setCapturingEnclosingInstance(false)
        .setFinal(false)
        .setFunctionalInterface(false)
        .setJsFunctionInterface(false)
        .setJsType(false)
        .setLocal(false)
        .setUnusableByJsSuppressed(false)
        .setDeprecated(false)
        .setTypeParameterDescriptors(ImmutableList.of())
        .setDeclaredMethodDescriptorsFactory(ImmutableMap::of)
        .setDeclaredFieldDescriptorsFactory(() -> ImmutableList.of())
        .setInterfaceTypeDescriptorsFactory(() -> ImmutableList.of())
        .setUnparameterizedTypeDescriptorFactory(unparameterizedFactory)
        .setSuperTypeDescriptorFactory(() -> null);
  }

  private static <T, U> U applyOrNull(T descriptor, Function<T, U> function) {
    return descriptor == null ? null : function.apply(descriptor);
  }

  /** Builder for a TypeDeclaration. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setAnonymous(boolean isAnonymous);

    public abstract Builder setClassComponents(List<String> classComponents);

    public abstract Builder setEnclosingTypeDeclaration(TypeDeclaration enclosingTypeDeclaration);

    public abstract Builder setHasAbstractModifier(boolean hasAbstractModifier);

    public abstract Builder setKind(Kind kind);

    public abstract Builder setCapturingEnclosingInstance(boolean capturingEnclosingInstance);

    public abstract Builder setFinal(boolean isFinal);

    public abstract Builder setFunctionalInterface(boolean isFunctionalInterface);

    public abstract Builder setJsFunctionInterface(boolean isJsFunctionInterface);

    public abstract Builder setJsType(boolean isJsType);

    public abstract Builder setJsEnumInfo(JsEnumInfo jsEnumInfo);

    public abstract Builder setUnusableByJsSuppressed(boolean isUnusableByJsSuppressed);

    public abstract Builder setDeprecated(boolean isDeprecated);

    public abstract Builder setLocal(boolean local);

    public abstract Builder setNative(boolean isNative);

    public abstract Builder setTypeParameterDescriptors(
        Iterable<TypeVariable> typeParameterDescriptors);

    public abstract Builder setVisibility(Visibility visibility);

    public abstract Builder setPackageName(String packageName);

    public abstract Builder setSimpleJsName(String simpleJsName);

    public abstract Builder setCustomizedJsNamespace(String jsNamespace);

    public abstract Builder setInterfaceTypeDescriptorsFactory(
        DescriptorFactory<ImmutableList<DeclaredTypeDescriptor>> interfaceTypeDescriptorsFactory);

    public Builder setInterfaceTypeDescriptorsFactory(
        Supplier<ImmutableList<DeclaredTypeDescriptor>> interfaceTypeDescriptorsFactory) {
      return setInterfaceTypeDescriptorsFactory(
          typeDescriptor -> interfaceTypeDescriptorsFactory.get());
    }

    public abstract Builder setSuperTypeDescriptorFactory(
        DescriptorFactory<DeclaredTypeDescriptor> superTypeDescriptorFactory);

    public Builder setSuperTypeDescriptorFactory(
        Supplier<DeclaredTypeDescriptor> superTypeDescriptorFactory) {
      return setSuperTypeDescriptorFactory(typeDescriptor -> superTypeDescriptorFactory.get());
    }

    public abstract Builder setUnparameterizedTypeDescriptorFactory(
        DescriptorFactory<DeclaredTypeDescriptor> unparameterizedTypeDescriptorFactory);

    public Builder setUnparameterizedTypeDescriptorFactory(
        Supplier<DeclaredTypeDescriptor> unparameterizedTypeDescriptorFactory) {
      return setUnparameterizedTypeDescriptorFactory(
          typeDescriptor -> unparameterizedTypeDescriptorFactory.get());
    }

    public abstract Builder setDeclaredMethodDescriptorsFactory(
        DescriptorFactory<ImmutableMap<String, MethodDescriptor>> declaredMethodDescriptorsFactory);

    public Builder setDeclaredMethodDescriptorsFactory(
        Supplier<ImmutableMap<String, MethodDescriptor>> declaredMethodDescriptorsFactory) {
      return setDeclaredMethodDescriptorsFactory(
          typeDescriptor -> declaredMethodDescriptorsFactory.get());
    }

    public abstract Builder setDeclaredFieldDescriptorsFactory(
        DescriptorFactory<ImmutableList<FieldDescriptor>> declaredFieldDescriptorsFactory);

    public Builder setDeclaredFieldDescriptorsFactory(
        Supplier<ImmutableList<FieldDescriptor>> declaredFieldDescriptorsFactory) {
      return setDeclaredFieldDescriptorsFactory(
          typeDescriptor -> declaredFieldDescriptorsFactory.get());
    }

    // Builder accessors to aid construction.
    abstract Optional<String> getPackageName();

    abstract ImmutableList<String> getClassComponents();

    abstract Optional<String> getSimpleJsName();

    abstract Optional<TypeDeclaration> getEnclosingTypeDeclaration();

    abstract boolean isNative();

    private static final ThreadLocalInterner<TypeDeclaration> interner =
        new ThreadLocalInterner<>();

    abstract TypeDeclaration autoBuild();

    public TypeDeclaration build() {
      if (!getPackageName().isPresent() && getEnclosingTypeDeclaration().isPresent()) {
        setPackageName(getEnclosingTypeDeclaration().get().getPackageName());
      }

      if (!getSimpleJsName().isPresent()) {
        setSimpleJsName(AstUtils.getSimpleSourceName(getClassComponents()));
      }

      TypeDeclaration typeDeclaration = autoBuild();

      // If this is an inner class, make sure the package is consistent.
      checkState(
          typeDeclaration.getEnclosingTypeDeclaration() == null
              || typeDeclaration
                  .getEnclosingTypeDeclaration()
                  .getPackageName()
                  .equals(typeDeclaration.getPackageName()));

      // Has to be an interface to be a functional interface.
      checkState(typeDeclaration.isInterface() || !typeDeclaration.isFunctionalInterface());

      checkState(
          typeDeclaration
              .getTypeParameterDescriptors()
              .stream()
              .noneMatch(TypeVariable::isWildcardOrCapture));
      return interner.intern(typeDeclaration);
    }

    public static Builder from(TypeDeclaration typeDeclaration) {
      return typeDeclaration.toBuilder();
    }
  }
}
