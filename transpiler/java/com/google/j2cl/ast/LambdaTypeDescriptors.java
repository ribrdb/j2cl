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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.j2cl.ast.MethodDescriptor.ParameterDescriptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Utility TypeDescriptors methods related to lambda synthesis. */
// TODO(b/63118697): Simplify this code once TD refactoring makes it easier to implement.
public class LambdaTypeDescriptors {
  private static final String FUNCTIONAL_INTERFACE_JSFUNCTION_CLASS_NAME = "JsFunction";
  private static final String FUNCTIONAL_INTERFACE_ADAPTOR_CLASS_NAME = "LambdaAdaptor";

  /** Returns the TypeDescriptor for a LambdaAdaptor class. */
  public static DeclaredTypeDescriptor createLambdaAdaptorTypeDescriptor(
      TypeDescriptor typeDescriptor) {
    return createLambdaAdaptorTypeDescriptor(
        typeDescriptor, (DeclaredTypeDescriptor) typeDescriptor, Optional.empty());
  }

  /** Returns the TypeDescriptor for lambda instances of the functional interface. */
  public static DeclaredTypeDescriptor createLambdaAdaptorTypeDescriptor(
      TypeDescriptor typeDescriptor,
      DeclaredTypeDescriptor enclosingTypeDescriptor,
      Optional<Integer> uniqueId) {

    DeclaredTypeDescriptor functionalInterfaceTypeDescriptor =
        typeDescriptor.getFunctionalInterface();

    checkArgument(!functionalInterfaceTypeDescriptor.isJsFunctionInterface());

    DeclaredTypeDescriptor jsFunctionInterface =
        createJsFunctionTypeDescriptor(functionalInterfaceTypeDescriptor);

    ImmutableList<TypeDescriptor> typeArgumentDescriptors =
        ImmutableList.<TypeDescriptor>builder()
            .addAll(functionalInterfaceTypeDescriptor.getTypeArgumentDescriptors())
            .addAll(
                functionalInterfaceTypeDescriptor
                    .getSingleAbstractMethodDescriptor()
                    .getTypeParameterTypeDescriptors())
            .build();

    List<DeclaredTypeDescriptor> interfaceTypeDescriptors =
        typeDescriptor.isIntersection()
            ? ((IntersectionTypeDescriptor) typeDescriptor).getIntersectionTypeDescriptors()
            : ImmutableList.of((DeclaredTypeDescriptor) typeDescriptor);

    TypeDeclaration adaptorDeclaration =
        createLambdaAdaptorTypeDeclaration(
            enclosingTypeDescriptor,
            interfaceTypeDescriptors,
            functionalInterfaceTypeDescriptor,
            jsFunctionInterface,
            uniqueId);

    return DeclaredTypeDescriptor.newBuilder()
        .setEnclosingTypeDescriptor(enclosingTypeDescriptor)
        .setTypeDeclaration(adaptorDeclaration)
        .setTypeArgumentDescriptors(functionalInterfaceTypeDescriptor.getTypeArgumentDescriptors())
        .setSuperTypeDescriptorFactory(() -> TypeDescriptors.get().javaLangObject)
        .setInterfaceTypeDescriptorsFactory(
            () -> TypeDescriptors.toUnparameterizedTypeDescriptors(interfaceTypeDescriptors))
        .setTypeArgumentDescriptors(typeArgumentDescriptors)
        .setDeclaredMethodDescriptorsFactory(
            adaptorTypeDescriptor ->
                getLambdaAdaptorMethodDescriptors(jsFunctionInterface, adaptorTypeDescriptor))
        .build();
  }

  /**
   * Returns the method descriptor for the methods in the LambdaAdaptor class.
   *
   * <p>The LambdaAdaptor class has two methods:
   * <li>a constructor taking the synthetic @JsFunction interface
   * <li>a SAM method implementation forwarding to the @JsFunction interface.
   */
  private static ImmutableMap<String, MethodDescriptor> getLambdaAdaptorMethodDescriptors(
      DeclaredTypeDescriptor jsFunctionInterface, DeclaredTypeDescriptor adaptorTypeDescriptor) {
    return createMethodDescriptorBySignatureMap(
        getLambdaAdaptorConstructor(jsFunctionInterface, adaptorTypeDescriptor),
        getAdaptorForwardingMethod(adaptorTypeDescriptor));
  }

  /** Returns the TypeDeclaration for the LambdaAdaptor class. */
  private static TypeDeclaration createLambdaAdaptorTypeDeclaration(
      DeclaredTypeDescriptor enclosingTypeDescriptor,
      List<DeclaredTypeDescriptor> interfaceTypeDescriptors,
      DeclaredTypeDescriptor functionalInterfaceTypeDescriptor,
      DeclaredTypeDescriptor jsFunctionInterface,
      Optional<Integer> uniqueId) {

    List<String> classComponents =
        enclosingTypeDescriptor.synthesizeInnerClassComponents(
            FUNCTIONAL_INTERFACE_ADAPTOR_CLASS_NAME, uniqueId.orElse(null));

    ImmutableList<TypeVariable> typeParameterDescriptors =
        ImmutableList.<TypeVariable>builder()
            .addAll(
                functionalInterfaceTypeDescriptor
                    .getTypeDeclaration()
                    .getTypeParameterDescriptors())
            .addAll(
                    functionalInterfaceTypeDescriptor
                        .toUnparameterizedTypeDescriptor()
                        .getSingleAbstractMethodDescriptor()
                        .getTypeParameterTypeDescriptors())
            .build();

    return TypeDeclaration.newBuilder()
        .setEnclosingTypeDeclaration(enclosingTypeDescriptor.getTypeDeclaration())
        .setSuperTypeDescriptorFactory(() -> TypeDescriptors.get().javaLangObject)
        .setClassComponents(classComponents)
        .setDeclaredMethodDescriptorsFactory(
            adaptorTypeDeclaration ->
                getLambdaAdaptorMethodDescriptors(
                    jsFunctionInterface, adaptorTypeDeclaration.toUnparameterizedTypeDescriptor()))
        .setInterfaceTypeDescriptorsFactory(
            () -> TypeDescriptors.toUnparameterizedTypeDescriptors(interfaceTypeDescriptors))
        .setTypeParameterDescriptors(typeParameterDescriptors)
        .setUnparameterizedTypeDescriptorFactory(
            () ->
                createLambdaAdaptorTypeDescriptor(
                    functionalInterfaceTypeDescriptor.toUnparameterizedTypeDescriptor(),
                    enclosingTypeDescriptor.toUnparameterizedTypeDescriptor(),
                    uniqueId))
        .setVisibility(Visibility.PUBLIC)
        .setKind(Kind.CLASS)
        .setTypeParameterDescriptors(typeParameterDescriptors)
        .build();
  }

  /** Returns the MethodDescriptor for the constructor of the LambdaAdaptor class. */
  private static MethodDescriptor getLambdaAdaptorConstructor(
      DeclaredTypeDescriptor jsFunctionInterface, DeclaredTypeDescriptor adaptorTypeDescriptor) {
    return MethodDescriptor.newBuilder()
        .setEnclosingTypeDescriptor(adaptorTypeDescriptor)
        .setConstructor(true)
        .setJsInfo(JsInfo.RAW_CTOR)
        .setOrigin(MethodDescriptor.MethodOrigin.SYNTHETIC_LAMBDA_ADAPTOR_CONSTRUCTOR)
        .setParameterTypeDescriptors(jsFunctionInterface)
        .build();
  }

  /** Returns the MethodDescriptor for the SAM implementation in the LambdaAdaptor class. */
  @SuppressWarnings("ReferenceEquality")
  public static MethodDescriptor getAdaptorForwardingMethod(
      DeclaredTypeDescriptor adaptorTypeDescriptor) {
    DeclaredTypeDescriptor functionalInterfaceTypeDescriptor =
        adaptorTypeDescriptor.getFunctionalInterface();
    checkState(
        functionalInterfaceTypeDescriptor.getFunctionalInterface()
            == functionalInterfaceTypeDescriptor);

    MethodDescriptor functionalInterfaceMethodDescriptor =
        functionalInterfaceTypeDescriptor.getSingleAbstractMethodDescriptor();
    return MethodDescriptor.Builder.from(functionalInterfaceMethodDescriptor)
        .setNative(false)
        // This is the declaration.
        .setDeclarationMethodDescriptor(
            createRelatedMethodDeclaration(
                LambdaTypeDescriptors::getAdaptorForwardingMethod, adaptorTypeDescriptor))
        .setEnclosingTypeDescriptor(adaptorTypeDescriptor)
        // Remove the method type parameters as they when moved to the adaptor type.
        .setTypeParameterTypeDescriptors(Collections.emptyList())
        .setSynthetic(false)
        .setAbstract(false)
        .build();
  }

  /** Returns the TypeDescriptor for lambda instances of the functional interface. */
  public static DeclaredTypeDescriptor createJsFunctionTypeDescriptor(
      TypeDescriptor typeDescriptor) {
    DeclaredTypeDescriptor functionalTypeDescriptor =
        typeDescriptor.getFunctionalInterface().toUnparameterizedTypeDescriptor();
    checkArgument(!functionalTypeDescriptor.isJsFunctionInterface());

    MethodDescriptor functionalMethodDescriptor =
        functionalTypeDescriptor.getSingleAbstractMethodDescriptor();

    // Remove varargs if the functional method is not a JsMethod, otherwise it will become
    // JsVarargs and loose runtime type checking on the varargs parameter.
    MethodDescriptor jsFunctionMethodDescriptor =
        functionalMethodDescriptor.isJsMethod()
            ? functionalMethodDescriptor
            : removeVarargs(functionalMethodDescriptor);

    TypeDeclaration jsFunctionDeclaration =
        createJsFunctionTypeDeclaration(functionalTypeDescriptor);

    return DeclaredTypeDescriptor.newBuilder()
        .setEnclosingTypeDescriptor(functionalTypeDescriptor)
        .setTypeDeclaration(jsFunctionDeclaration)
        .setTypeArgumentDescriptors(functionalTypeDescriptor.getTypeArgumentDescriptors())
        .setSingleAbstractMethodDescriptorFactory(
            jsfunctionTypeDescriptor ->
                createJsFunctionMethodDescriptor(
                    jsfunctionTypeDescriptor, jsFunctionMethodDescriptor))
        .setJsFunctionMethodDescriptorFactory(
            DeclaredTypeDescriptor::getSingleAbstractMethodDescriptor)
        .setDeclaredMethodDescriptorsFactory(
            jsfunctionTypeDescriptor ->
                createMethodDescriptorBySignatureMap(
                    jsfunctionTypeDescriptor.getSingleAbstractMethodDescriptor()))
        .build();
  }

  /**
   * Removes the varargs attribute from the varargs parameter if {@code functionalMethodDescriptor}
   * is a varargs method.
   */
  private static MethodDescriptor removeVarargs(MethodDescriptor functionalMethodDescriptor) {
    return MethodDescriptor.Builder.from(functionalMethodDescriptor)
        .setParameterDescriptors(
            functionalMethodDescriptor.getParameterDescriptors().stream()
                .map(
                    parameterDescriptor ->
                        ParameterDescriptor.newBuilder()
                            .setTypeDescriptor(parameterDescriptor.getTypeDescriptor())
                            .build())
                .collect(ImmutableList.toImmutableList()))
        .setJsInfo(
            JsInfo.Builder.from(functionalMethodDescriptor.getJsInfo())
                .setJsMemberType(JsMemberType.NONE)
                .build())
        .build();
  }

  /** Returns the TypeDeclaration for the JsFunction class. */
  private static TypeDeclaration createJsFunctionTypeDeclaration(
      DeclaredTypeDescriptor functionalTypeDescriptor) {

    List<String> classComponents =
        functionalTypeDescriptor.synthesizeInnerClassComponents(
            FUNCTIONAL_INTERFACE_JSFUNCTION_CLASS_NAME);

    return TypeDeclaration.newBuilder()
        .setEnclosingTypeDeclaration(functionalTypeDescriptor.getTypeDeclaration())
        .setTypeParameterDescriptors(
            functionalTypeDescriptor.getTypeDeclaration().getTypeParameterDescriptors())
        .setClassComponents(classComponents)
        .setJsFunctionInterface(true)
        .setFunctionalInterface(true)
        .setDeclaredMethodDescriptorsFactory(
            jsfunctionTypeDeclaration ->
                createMethodDescriptorBySignatureMap(
                    createJsFunctionMethodDescriptor(
                        jsfunctionTypeDeclaration.toUnparameterizedTypeDescriptor(),
                        functionalTypeDescriptor.getSingleAbstractMethodDescriptor())))
        .setUnparameterizedTypeDescriptorFactory(
            () ->
                createJsFunctionTypeDescriptor(
                    functionalTypeDescriptor.toUnparameterizedTypeDescriptor()))
        .setVisibility(Visibility.PUBLIC)
        .setKind(Kind.INTERFACE)
        .build();
  }

  /** Returns the MethodDescriptor for the single method in the synthetic @JsFunction interface. */
  private static MethodDescriptor createJsFunctionMethodDescriptor(
      DeclaredTypeDescriptor jsfunctionTypeDescriptor, MethodDescriptor singleAbstractMethod) {
    return MethodDescriptor.Builder.from(singleAbstractMethod)
        .setEnclosingTypeDescriptor(jsfunctionTypeDescriptor)
        .setDeclarationMethodDescriptor(
            createRelatedMethodDeclaration(
                t ->
                    createJsFunctionMethodDescriptor(
                        t, singleAbstractMethod.getDeclarationDescriptor()),
                jsfunctionTypeDescriptor))
        .setJsInfo(
            JsInfo.newBuilder()
                .setJsMemberType(JsMemberType.NONE)
                .setJsAsync(singleAbstractMethod.getJsInfo().isJsAsync())
                .build())
        .setNative(false)
        .setJsFunction(true)
        .build();
  }

  private static ImmutableMap<String, MethodDescriptor> createMethodDescriptorBySignatureMap(
      MethodDescriptor... methodDescriptors) {
    return Arrays.stream(methodDescriptors)
        .collect(toImmutableMap(MethodDescriptor::getMethodSignature, Function.identity()));
  }

  private static MethodDescriptor createRelatedMethodDeclaration(
      Function<DeclaredTypeDescriptor, MethodDescriptor> creator,
      DeclaredTypeDescriptor typeDescriptor) {
    DeclaredTypeDescriptor unparameterizedTypeDescriptor =
        typeDescriptor.toUnparameterizedTypeDescriptor();
    if (unparameterizedTypeDescriptor.equals(typeDescriptor)) {
      return null;
    }
    return creator.apply(unparameterizedTypeDescriptor);
  }
}
