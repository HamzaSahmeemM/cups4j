package de.spqrinfo.vppserver.ippclient;

import org.hamcrest.CoreMatchers;
import static org.hamcrest.MatcherAssert.assertThat;
import org.junit.Test;

public class IppJaxbTest {

    @Test
    public void constructorTest() throws Exception {
        final IppJaxb obj = new IppJaxb();
        assertThat(obj, CoreMatchers.notNullValue());
    }
}
