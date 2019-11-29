package com.timboudreau.metaupdatecenter;

import java.util.Comparator;

/**
 *
 * @author Tim Boudreau
 */
class ModuleItemComparator implements Comparator<ModuleItem> {

    @Override
    public int compare(ModuleItem t, ModuleItem t1) {
        return t.getName().compareTo(t1.getName());
    }

}
