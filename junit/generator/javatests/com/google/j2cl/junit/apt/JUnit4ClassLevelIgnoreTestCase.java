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

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A unit test to test processing handling of class level ignore. */
@RunWith(JUnit4.class)
@Ignore
public class JUnit4ClassLevelIgnoreTestCase extends JUnit4AbstractTestCase {
  @Override
  @Test
  public void testOverriddenWithTest() {}

  @Override
  public void testOverriddenWithoutTest() {}

  @Override
  @Test
  @Ignore
  public void testOverriddenWithTestAndIgnore() {}

  @Override
  @Ignore
  public void testOverriddenWithIgnoreButNoTest() {}
}
