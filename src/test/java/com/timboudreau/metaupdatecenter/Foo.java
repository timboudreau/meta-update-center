/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.timboudreau.metaupdatecenter;

import com.mastfrog.util.time.TimeUtil;
import java.time.ZonedDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 *
 * @author Tim Boudreau
 */
public class Foo {

    public static void main(String[] args) {
        ZonedDateTime now = ZonedDateTime.now().withNano(0);
        long unix = TimeUtil.toUnixTimestamp(now);

        System.out.println("NOW: " + now);
        System.out.println("UNIX: " + unix);

        System.out.println("FMT: " + TimeUtil.toIsoFormat(now));

        ZonedDateTime parsed = TimeUtil.fromIsoFormat("2019-11-26T20:30:42Z");
        assertEquals(now, parsed);
        System.out.println("PARSED " + parsed);
    }
}
