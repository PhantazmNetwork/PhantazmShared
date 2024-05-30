package org.phantazm.commons.algebra;

import org.junit.jupiter.api.Test;

class ParserTest {
    @Test
    void test() {
        Parser.parse("------+-+-+-+-+-+-+-+-+-+-+-+-+-++-+-+-+x---+y/sin(x+----y/2^2)");
    }
}