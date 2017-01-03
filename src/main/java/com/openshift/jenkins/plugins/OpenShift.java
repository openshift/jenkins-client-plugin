package com.openshift.jenkins.plugins;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import java.util.List;

public class OpenShift extends AbstractDescribableImpl<OpenShift> {

    public static final String DEFAULT_LOGLEVEL = "0";

    @Extension
    public static class DescriptorImpl extends Descriptor<OpenShift> {

        // Store a config version so we're able to migrate config.
        public Long configVersion;

        public List<ClusterConfig> clusterConfigs;

        public String ocTool = null;

        public String logLevel = DEFAULT_LOGLEVEL;


        public DescriptorImpl() {
            configVersion = 1L;
            load();
        }

        @Override
        public String getDisplayName() {
            return "OpenShift Configuration";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {

            /**
             * If all cluster configurations are deleted in the UI and
             * saved, binJSON does not set the list. So clear out the list
             * before bind.
             */
            clusterConfigs = null;

            req.bindJSON(this, json.getJSONObject("openshift"));
            save();
            return true;
        }

        // Creates a model that fills in logLevel options in configuration UI
        public ListBoxModel doFillLogLevelItems() {
            ListBoxModel items = new ListBoxModel();
            items.add( "0 - Least Logging", "0" );
            for ( int i = 1; i < 10; i++ ) {
                items.add( ""+i, ""+i );
            }
            items.add( "10 - Most Logging", "10" );
            return items;
        }

        /**
         * Determines if a cluster has been configured with a given name.
         * If a cluster has been configured with the name, its definition
         * is returned.
         * @param name The name of the cluster config to find
         * @return A ClusterConfig for the supplied parameters OR null.
         */
        public ClusterConfig getClusterConfig( String name ) {
            if ( clusterConfigs == null ) {
                return null;
            }

            name = Util.fixEmptyAndTrim( name );
            for ( ClusterConfig cc : clusterConfigs ) {
                if ( cc.getName().equalsIgnoreCase( name ) ) {
                    return cc;
                }
            }
            return null;
        }

    }

}
