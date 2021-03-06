/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.highlighter;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("idea/testData/exitPoints")
@TestDataPath("$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class HighlightExitPointsTestGenerated extends AbstractHighlightExitPointsTest {
    public void testAllFilesPresentInExitPoints() throws Exception {
        KotlinTestUtils.assertAllTestsPresentByMetadata(this.getClass(), new File("idea/testData/exitPoints"), Pattern.compile("^(.+)\\.kt$"), true);
    }

    @TestMetadata("inline1.kt")
    public void testInline1() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/inline1.kt");
        doTest(fileName);
    }

    @TestMetadata("inline2.kt")
    public void testInline2() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/inline2.kt");
        doTest(fileName);
    }

    @TestMetadata("inlineLocalReturn1.kt")
    public void testInlineLocalReturn1() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/inlineLocalReturn1.kt");
        doTest(fileName);
    }

    @TestMetadata("inlineLocalReturn2.kt")
    public void testInlineLocalReturn2() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/inlineLocalReturn2.kt");
        doTest(fileName);
    }

    @TestMetadata("inlineWithNoInlineParam.kt")
    public void testInlineWithNoInlineParam() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/inlineWithNoInlineParam.kt");
        doTest(fileName);
    }

    @TestMetadata("invalidReturn.kt")
    public void testInvalidReturn() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/invalidReturn.kt");
        doTest(fileName);
    }

    @TestMetadata("invalidThrow.kt")
    public void testInvalidThrow() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/invalidThrow.kt");
        doTest(fileName);
    }

    @TestMetadata("localFunction1.kt")
    public void testLocalFunction1() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/localFunction1.kt");
        doTest(fileName);
    }

    @TestMetadata("localFunction2.kt")
    public void testLocalFunction2() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/localFunction2.kt");
        doTest(fileName);
    }

    @TestMetadata("localFunctionThrow.kt")
    public void testLocalFunctionThrow() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/localFunctionThrow.kt");
        doTest(fileName);
    }

    @TestMetadata("notInline1.kt")
    public void testNotInline1() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/notInline1.kt");
        doTest(fileName);
    }

    @TestMetadata("notInline2.kt")
    public void testNotInline2() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/notInline2.kt");
        doTest(fileName);
    }

    @TestMetadata("simple.kt")
    public void testSimple() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/simple.kt");
        doTest(fileName);
    }

    @TestMetadata("throw1.kt")
    public void testThrow1() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/throw1.kt");
        doTest(fileName);
    }

    @TestMetadata("throw2.kt")
    public void testThrow2() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/exitPoints/throw2.kt");
        doTest(fileName);
    }
}
