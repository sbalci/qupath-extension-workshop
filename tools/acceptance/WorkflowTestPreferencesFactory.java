package io.github.sbalci.qupath.workshop.acceptance;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Test-process-only preferences. GUI acceptance must not change the user's registry/profile. */
public final class WorkflowTestPreferencesFactory implements PreferencesFactory {
    private static final Preferences USER = new MemoryNode(null, "");
    private static final Preferences SYSTEM = new MemoryNode(null, "");
    public Preferences userRoot() { return USER; }
    public Preferences systemRoot() { return SYSTEM; }
    private static final class MemoryNode extends AbstractPreferences {
        private final Map<String,String> values=new HashMap<>();
        private final Map<String,MemoryNode> children=new HashMap<>();
        MemoryNode(AbstractPreferences parent,String name){super(parent,name);}
        protected void putSpi(String key,String value){values.put(key,value);}
        protected String getSpi(String key){return values.get(key);}
        protected void removeSpi(String key){values.remove(key);}
        protected void removeNodeSpi(){values.clear();children.clear();}
        protected String[] keysSpi(){return values.keySet().toArray(String[]::new);}
        protected String[] childrenNamesSpi(){return children.keySet().toArray(String[]::new);}
        protected AbstractPreferences childSpi(String name){return children.computeIfAbsent(name,n->new MemoryNode(this,n));}
        protected void syncSpi(){}
        protected void flushSpi(){}
    }
}
