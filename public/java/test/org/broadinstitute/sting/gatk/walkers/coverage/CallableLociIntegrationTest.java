/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.sting.gatk.walkers.coverage;

import org.broadinstitute.sting.WalkerTest;
import org.testng.annotations.Test;

import java.util.Arrays;

public class CallableLociIntegrationTest extends WalkerTest {
    final static String commonArgs     = "-R " + b36KGReference + " -T CallableLoci -I " + validationDataLocation + "/NA12878.1kg.p2.chr1_10mb_11_mb.SLX.bam -o %s";
    final static String reduceReadArgs = "-R " + b37KGReference + " -T CallableLoci -I " + " private/testdata/NA12878.HiSeq.b37.chr20.10_11mb.reduced.bam -o %s";

    final static String SUMMARY_MD5 = "ffdbd9cdcb4169ebed5ae4bec797260f";

    @Test
    public void testCallableLociWalkerBed() {
        String gatk_args = commonArgs + " -format BED -L 1:10,000,000-11,000,000 -summary %s";
        WalkerTestSpec spec = new WalkerTestSpec(gatk_args, 2,
                Arrays.asList("42e86c06c167246a28bffdacaca75ffb", SUMMARY_MD5));
        executeTest("formatBed", spec);
    }

    @Test
    public void testCallableLociWalkerPerBase() {
        String gatk_args = commonArgs + " -format STATE_PER_BASE -L 1:10,000,000-11,000,000 -summary %s";
        WalkerTestSpec spec = new WalkerTestSpec(gatk_args, 2,
                Arrays.asList("d66c525d9c70f62df8156261d3e535ad", SUMMARY_MD5));
        executeTest("format_state_per_base", spec);
    }
    
    @Test
    public void testCallableLociWalker2() {
        String gatk_args = commonArgs + " -format BED -L 1:10,000,000-10,000,100 -L 1:10,000,110-10,000,120 -summary %s";
        WalkerTestSpec spec = new WalkerTestSpec(gatk_args, 2,
                Arrays.asList("330f476085533db92a9dbdb3a127c041", "d287510eac04acf5a56f5cde2cba0e4a"));
        executeTest("formatBed by interval", spec);
    }

    @Test
    public void testCallableLociWalker3() {
        String gatk_args = commonArgs + " -format BED -L 1:10,000,000-11,000,000 -minDepth 10 -maxDepth 100 --minBaseQuality 10 --minMappingQuality 20 -summary %s";
        WalkerTestSpec spec = new WalkerTestSpec(gatk_args, 2,
                Arrays.asList("46a53379aaaf9803276a0a34b234f6ab", "da431d393f7c2b2b3e27556b86c1dbc7"));
        executeTest("formatBed lots of arguments", spec);
    }

    @Test(enabled=true)
    public void testWithReducedRead() {
        String gatk_args = reduceReadArgs + " -L 20:10,000,000-11,000,000 -minDepth 10 -maxDepth 100 --minBaseQuality 10 --minMappingQuality 20 -summary %s";
        WalkerTestSpec spec = new WalkerTestSpec(gatk_args, 1,
                Arrays.asList("69fc303c888fd1fa2937b9518dc82f9e", "f512a85c373087ce03a24ab0f98522c0"));
        executeTest("CallableLoci with ReducedRead", spec);
    }

}