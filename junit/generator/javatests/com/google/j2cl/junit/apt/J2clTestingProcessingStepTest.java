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
package com.google.j2cl.junit.apt;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import com.google.j2cl.junit.integration.async.data.TestReturnTypeNotStructuralPromise;
import com.google.j2cl.junit.integration.async.data.TestReturnTypeNotStructuralPromiseThenParameterCount;
import com.google.j2cl.junit.integration.async.data.TestReturnTypeNotStructuralPromiseThenParameterNotJsType;
import com.google.j2cl.junit.integration.async.data.TestReturnsVoidTimeoutProvided;
import com.google.j2cl.junit.integration.async.data.TestTimeOutNotProvided;
import com.google.j2cl.junit.integration.async.data.TestWithExpectedException;
import com.google.j2cl.junit.integration.async.data.TestWithLifeCycleMethodBeingAsync;
import com.google.testing.compile.CompilationRule;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test for {@link J2clTestingProcessingStep}. */
@RunWith(JUnit4.class)
public class J2clTestingProcessingStepTest {

  @Rule public CompilationRule compilation = new CompilationRule();
  Messager messager;

  @Before
  public void setup() {
    messager = mock(Messager.class);
  }

  @Test
  public void testSimpleJUnit3TestCase() {
    TestClass testClass = executeProcessorOnTest(SimpleJUnit3TestCase.class);
    assertThat(testClass.packageName()).isEqualTo("com.google.j2cl.junit.apt");
    assertThat(testClass.simpleName()).isEqualTo("SimpleJUnit3TestCase");
    assertThat(testClass.afterClassMethods()).isEmpty();
    assertThat(testClass.beforeClassMethods()).isEmpty();
    assertThat(testClass.afterMethods()).containsExactly(method("__hiddenTearDown"));
    assertThat(testClass.beforeMethods()).containsExactly(method("__hiddenSetUp"));
    assertThat(testClass.testMethods())
        .containsExactly(method("testMethod1"), method("testMethod2"))
        .inOrder();
  }

  @Test
  public void testAdvancedJUnit3TestCase() {
    TestClass testClass = executeProcessorOnTest(AdvancedJUnit3TestCase.class);
    assertThat(testClass.packageName()).isEqualTo("com.google.j2cl.junit.apt");
    assertThat(testClass.simpleName()).isEqualTo("AdvancedJUnit3TestCase");
    assertThat(testClass.afterClassMethods()).isEmpty();
    assertThat(testClass.beforeClassMethods()).isEmpty();
    assertThat(testClass.afterMethods()).containsExactly(method("__hiddenTearDown"));
    assertThat(testClass.beforeMethods()).containsExactly(method("__hiddenSetUp"));
    assertThat(testClass.testMethods())
        .containsExactly(
            method("test"),
            method("test1"),
            method("testOverridenFromGrandParent"),
            method("testOverridenFromParent"),
            method("testParent1"),
            method("testParent"),
            method("testFromGrandParent1"),
            method("testFromGrandParent"))
        .inOrder();
  }


  @Test
  public void testJUnit4NonPublicMethod() {
    assertError(ErrorMessage.NON_PUBLIC, JUnit4TestCaseWithNonPublicTestMethod.class, "test");
  }

  @Test
  public void testJUnit4StaticMethod() {
    assertError(ErrorMessage.IS_STATIC, JUnit4TestCaseWithStaticTestMethod.class, "test");
  }

  @Test
  public void testJUnit4NonVoidReturnTypeMethod() {
    assertError(
        ErrorMessage.NON_PROMISE_RETURN,
        TestReturnTypeNotStructuralPromise.class,
        "returnTypeNotStructuralPromise");
  }

  @Test
  public void testJUnit4ArgumentsOnMethod() {
    assertError(ErrorMessage.HAS_ARGS, JUnit4TestCaseWithArgumentsOnMethod.class, "test");
  }

  @Test
  public void testJUnit4InnerClass() {
    assertError(ErrorMessage.NON_TOP_LEVEL_TYPE, JUnit4TestCaseOnInnerClass.Inner.class);
  }

  @Test
  public void testNonJunit4Suite() {
    assertError(ErrorMessage.JUNIT3_SUITE, JUnit3Suite.class);
    assertError(ErrorMessage.EMPTY_SUITE, EmptySuite.class);
    assertError(ErrorMessage.SKIPPED_TYPE, RandomDependency.class);
  }

  @Test
  public void testNoTestInput() {
    J2clTestingProcessingStep step = createProcessor();
    step.writeSummary();
    verifyPrintMessage(ErrorMessage.NO_TEST_INPUT, null);
  }

  @Test
  public void testJUnit3NonPublicMethod() {
    assertError(ErrorMessage.NON_PUBLIC, JUnit3TestCaseWithNonPublicTestMethod.class, "test");
  }


  @Test
  public void testJUnit3StaticMethod() {
    TestClass testClass = executeProcessorOnTest(JUnit3TestCaseWithStaticTestMethod.class);
    assertThat(testClass.packageName()).isEqualTo("com.google.j2cl.junit.apt");
    assertThat(testClass.simpleName()).isEqualTo("JUnit3TestCaseWithStaticTestMethod");
    assertThat(testClass.afterClassMethods()).isEmpty();
    assertThat(testClass.beforeClassMethods()).isEmpty();
    assertThat(testClass.afterMethods()).containsExactly(method("__hiddenTearDown"));
    assertThat(testClass.beforeMethods()).containsExactly(method("__hiddenSetUp"));
    assertThat(testClass.testMethods()).containsExactly(staticMethod("test"));
  }

  @Test
  public void testJUnit3NonVoidReturnTypeMethod() {
    assertError(
        ErrorMessage.NON_VOID_RETURN,
        JUnit3TestCaseNonVoidReturnTypeTestMethod.class,
        "testIllegal");
  }

  @Test
  public void testJUnit3InnerClass() {
    assertError(ErrorMessage.NON_TOP_LEVEL_TYPE, JUnit3TestCaseOnInnerClass.Inner.class);
  }

  @Test
  public void testAsyncTestWithoutTimeout() {
    assertError(ErrorMessage.ASYNC_NO_TIMEOUT, TestTimeOutNotProvided.class, "doesNotHaveTimeout");
  }

  @Test
  public void testAsyncTestWithExpectedException() {
    assertError(ErrorMessage.ASYNC_HAS_EXPECTED_EXCEPTION, TestWithExpectedException.class, "test");
  }

  @Test
  public void testAsyncTestWithLifeCycleMethodBeingAsync() {
    assertError(ErrorMessage.NON_VOID_RETURN, TestWithLifeCycleMethodBeingAsync.class, "before");
  }

  @Test
  public void testAsyncTestThenParameterCount() {
    assertError(
        ErrorMessage.NON_PROMISE_RETURN,
        TestReturnTypeNotStructuralPromiseThenParameterCount.class,
        "test");
  }

  @Test
  public void testAsyncTestThenParameterNotJsType() {
    assertError(
        ErrorMessage.NON_PROMISE_RETURN,
        TestReturnTypeNotStructuralPromiseThenParameterNotJsType.class,
        "test");
  }

  @Test
  public void testNonAsyncTestProvidesTimeout() {
    assertError(ErrorMessage.HAS_TIMEOUT, TestReturnsVoidTimeoutProvided.class, "test");
  }

  @Test
  public void testOverriddenTests() {
    TestClass concreteTestClass = executeProcessorOnTest(JUnit4ConcreteSubclassTestCase.class);
    assertThat(concreteTestClass.testMethods())
        .containsExactly(method("testOverriddenWithoutTest"), method("testOverriddenWithTest"))
        .inOrder();
  }

  @Test
  public void testClassLevelIgnore() {
    TestClass concreteTestClass = executeProcessorOnTest(JUnit4ClassLevelIgnoreTestCase.class);
    assertThat(concreteTestClass.testMethods()).isEmpty();
  }

  private void assertError(ErrorMessage expectedError, Class<?> testClass) {
    assertMessage(expectedError, testClass.getCanonicalName(), testClass);
  }

  private void assertError(ErrorMessage expectedError, Class<?> testClass, String methodName) {
    assertMessage(expectedError, testClass.getCanonicalName() + "." + methodName, testClass);
  }

  private void assertMessage(ErrorMessage expectedError, String errorArg, Class<?> test) {
    TestClass testClass = executeProcessorOnTest(test);
    assertThat(testClass).isNull();
    verifyPrintMessage(expectedError, errorArg);
  }

  private void verifyPrintMessage(ErrorMessage expectedError, String errorArg) {
    verify(messager).printMessage(expectedError.kind(), expectedError.format(errorArg));
    verifyNoMoreInteractions(messager);
  }

  private TestClass executeProcessorOnTest(Class<?> test) {
    J2clTestingProcessingStep step = createProcessor();
    step.handleClass(test.getCanonicalName());
    return Iterables.getOnlyElement(step.getTestClasses(), null);
  }

  private J2clTestingProcessingStep createProcessor() {
    ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
    when(processingEnv.getTypeUtils()).thenReturn(compilation.getTypes());
    when(processingEnv.getElementUtils()).thenReturn(compilation.getElements());
    when(processingEnv.getMessager()).thenReturn(messager);
    when(processingEnv.getFiler()).thenReturn(mock(Filer.class));
    return new J2clTestingProcessingStep(processingEnv);
  }

  // TODO(goktug): Remove after testAdvancedJUnit3TestCase is moved to integration test and update
  // the visibilities of used classes.
  private TestMethod method(String methodName) {
    return TestMethod.builder().javaMethodName(methodName).isStatic(false).build();
  }

  private TestMethod staticMethod(String methodName) {
    return TestMethod.builder().javaMethodName(methodName).isStatic(true).build();
  }
}
