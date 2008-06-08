//============================================================================
//
// Copyright (C) 2002-2007  David Schneider, Lars K�dderitzsch
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//============================================================================

package net.sf.eclipsecs.core.config;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.eclipsecs.core.CheckstylePlugin;
import net.sf.eclipsecs.core.Messages;
import net.sf.eclipsecs.core.config.configtypes.BuiltInConfigurationType;
import net.sf.eclipsecs.core.config.configtypes.ConfigurationTypes;
import net.sf.eclipsecs.core.config.configtypes.IConfigurationType;
import net.sf.eclipsecs.core.util.CheckstyleLog;
import net.sf.eclipsecs.core.util.CheckstylePluginException;
import net.sf.eclipsecs.core.util.XMLUtil;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ObjectUtils;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Used to manage the life cycle of <code>CheckConfiguration</code> objects.
 */
public final class CheckConfigurationFactory {

    /** Name of the internal file storing the plugin check configurations. */
    protected static final String CHECKSTYLE_CONFIG_FILE = "checkstyle-config.xml"; //$NON-NLS-1$

    /** Name of the actual config file version. */
    private static final String VERSION_5_0_0 = "5.0.0"; //$NON-NLS-1$

    /** The current file version. */
    protected static final String CURRENT_CONFIG_FILE_FORMAT_VERSION = VERSION_5_0_0;

    /** constant for the extension point id. */
    private static final String CONFIGS_EXTENSION_POINT = CheckstylePlugin.PLUGIN_ID
            + ".configurations"; //$NON-NLS-1$

    /**
     * List of known check configurations. Synchronized because of possible
     * concurrend access.
     */
    private static List<ICheckConfiguration> sConfigurations = Collections
            .synchronizedList(new ArrayList<ICheckConfiguration>());

    private static ICheckConfiguration sDefaultCheckConfig;

    static {
        refresh();
    }

    private CheckConfigurationFactory() {}

    /**
     * Get an <code>CheckConfiguration</code> instance by its name.
     * 
     * @param name Name of the requested instance.
     * @return The requested instance or <code>null</code> if the named
     *         instance could not be found.
     */
    public static ICheckConfiguration getByName(String name) {

        for (ICheckConfiguration config : sConfigurations) {
            if (config.getName().equals(name)) {
                return config;
            }
        }

        return null;
    }

    /**
     * Get a list of the currently defined check configurations.
     * 
     * @return A list containing all instances.
     */
    public static List<ICheckConfiguration> getCheckConfigurations() {
        return Collections.unmodifiableList(sConfigurations);
    }

    /**
     * Returns the default check configuration if one is set, if none is set the
     * Sun Checks built-in configuration will be returned.
     * 
     * @return the default check configuration to use with unconfigured projects
     */
    public static ICheckConfiguration getDefaultCheckConfiguration() {
        if (sDefaultCheckConfig != null) {
            return sDefaultCheckConfig;
        }
        else if (sConfigurations.size() > 0) {
            return sConfigurations.get(0);
        }
        else {
            return null;
        }
    }

    /**
     * Creates a new working set from the existing configurations.
     * 
     * @return a new configuration working set
     */
    public static ICheckConfigurationWorkingSet newWorkingSet() {
        return new GlobalCheckConfigurationWorkingSet(sConfigurations,
                getDefaultCheckConfiguration());
    }

    /**
     * Refreshes the check configurations from the persistent store.
     */
    public static void refresh() {
        try {
            sConfigurations.clear();
            loadBuiltinConfigurations();
            loadFromPersistence();

        }
        catch (CheckstylePluginException e) {
            CheckstyleLog.log(e);
        }
    }

    /**
     * Copy the checkstyle configuration of a check configuration into another
     * configuration.
     * 
     * @param source the source check configuration
     * @param target the target check configuartion
     * @throws CheckstylePluginException Error copying the configuration
     */
    public static void copyConfiguration(ICheckConfiguration source, ICheckConfiguration target)
        throws CheckstylePluginException {
        // use the export function ;-)
        String targetFile = target.getResolvedConfigurationFileURL().getFile();

        String sourceFile = source.getResolvedConfigurationFileURL().getFile();

        // copying from a file to the same file will destroy it.
        if (ObjectUtils.equals(targetFile, sourceFile)) {
            return;
        }

        exportConfiguration(new File(targetFile), source);
    }

    /**
     * Write check configurations to an external file in standard Checkstyle
     * format.
     * 
     * @param file File to write too.
     * @param config List of check configurations to write out.
     * @throws CheckstylePluginException Error during export.
     */
    public static void exportConfiguration(File file, ICheckConfiguration config)
        throws CheckstylePluginException {

        InputStream in = null;
        OutputStream out = null;

        try {

            // Just copy the checkstyle configuration
            in = config.getCheckstyleConfiguration().getCheckConfigFileStream();
            out = new BufferedOutputStream(new FileOutputStream(file));

            IOUtils.copy(in, out);
        }
        catch (Exception e) {
            CheckstylePluginException.rethrow(e);
        }
        finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * Load the check configurations from the persistent state storage.
     */
    private static void loadFromPersistence() throws CheckstylePluginException {

        InputStream inStream = null;

        try {

            IPath configPath = CheckstylePlugin.getDefault().getStateLocation();
            configPath = configPath.append(CHECKSTYLE_CONFIG_FILE);
            File configFile = configPath.toFile();

            //
            // Make sure the files exists, it might not.
            //
            if (!configFile.exists()) {
                return;
            }
            else {
                inStream = new BufferedInputStream(new FileInputStream(configFile));
            }

            CheckConfigurationsFileHandler handler = new CheckConfigurationsFileHandler();
            XMLUtil.parseWithSAX(inStream, handler);

            if (handler.isOldFileFormat()) {
                throw new CheckstylePluginException(
                        "eclipse-cs version 3.x type configuration files are not supported anymore.");
            }
            else {
                sConfigurations.addAll(handler.getConfigurations());

                String defaultConfigName = handler.getDefaultCheckConfigurationName();

                for (ICheckConfiguration config : sConfigurations) {
                    if (config.getName().equals(defaultConfigName)) {
                        sDefaultCheckConfig = config;
                    }
                }
            }
        }
        catch (Exception e) {
            CheckstylePluginException.rethrow(e, Messages.errorLoadingConfigFile);
        }

        finally {
            IOUtils.closeQuietly(inStream);
        }
    }

    /**
     * Loads the built-in check configurations defined in plugin.xml or custom
     * fragments.
     */
    private static void loadBuiltinConfigurations() {

        IExtensionRegistry pluginRegistry = Platform.getExtensionRegistry();

        IConfigurationElement[] elements = pluginRegistry
                .getConfigurationElementsFor(CONFIGS_EXTENSION_POINT);

        for (int i = 0; i < elements.length; i++) {
            String name = elements[i].getAttribute(XMLTags.NAME_TAG);
            String description = elements[i].getAttribute(XMLTags.DESCRIPTION_TAG);
            String location = elements[i].getAttribute(XMLTags.LOCATION_TAG);

            IConfigurationType configType = new BuiltInConfigurationType();

            Map<String, String> additionalData = new HashMap<String, String>();
            additionalData.put(BuiltInConfigurationType.CONTRIBUTOR_KEY, elements[i]
                    .getContributor().getName());

            ICheckConfiguration checkConfig = new CheckConfiguration(name, location, description,
                    configType, true, null, additionalData);
            sConfigurations.add(checkConfig);
        }
    }

    /**
     * SAX-DefaultHandler for parsing the check-configurations file.
     * 
     * @author Lars K�dderitzsch
     */
    private static class CheckConfigurationsFileHandler extends DefaultHandler {
        /** the configurations read from the xml. */
        private final List<ICheckConfiguration> mConfigurations = new ArrayList<ICheckConfiguration>();

        /** Flags if the old plugin configuration file format was detected. */
        private boolean mOldFileFormat;

        /** The name of the current check configuration. */
        private String mCurrentName;

        /** The location of the current check configuration. */
        private String mCurrentLocation;

        /** The description of the current check configuration. */
        private String mCurrentDescription;

        /** The configuration type of the current configuration. */
        private IConfigurationType mCurrentConfigType;

        /** Additional data for the current configuration. */
        private Map<String, String> mCurrentAddValues;

        /** List of resolvable properties for this configuration. */
        private List<ResolvableProperty> mResolvableProperties;

        /** The default check configuration name. */
        private String mDefaultConfigName;

        /**
         * Return the configurations this handler built.
         * 
         * @return the configurations
         */
        public List<ICheckConfiguration> getConfigurations() {
            return mConfigurations;
        }

        /**
         * Returns the default check configuration name or <code>null</code>
         * if none was specified.
         * 
         * @return the default check configuration name or <code>null</code>
         */
        public String getDefaultCheckConfigurationName() {
            return mDefaultConfigName;
        }

        /**
         * Returns if the old plugin file format was detected.
         * 
         * @return <code>true</code> if the old file format was detected
         */
        public boolean isOldFileFormat() {
            return mOldFileFormat;
        }

        /**
         * {@inheritDoc}
         */
        public void startElement(String uri, String localName, String qName, Attributes attributes) {

            if (XMLTags.CHECKSTYLE_ROOT_TAG.equals(qName)) {
                String version = attributes.getValue(XMLTags.VERSION_TAG);

                if (!CURRENT_CONFIG_FILE_FORMAT_VERSION.equals(version)) {
                    mOldFileFormat = true;
                }

                mDefaultConfigName = attributes.getValue(XMLTags.DEFAULT_CHECK_CONFIG_TAG);
            }

            else if (!mOldFileFormat && XMLTags.CHECK_CONFIG_TAG.equals(qName)) {
                mCurrentName = attributes.getValue(XMLTags.NAME_TAG);
                mCurrentDescription = attributes.getValue(XMLTags.DESCRIPTION_TAG);
                mCurrentLocation = attributes.getValue(XMLTags.LOCATION_TAG);

                String type = attributes.getValue(XMLTags.TYPE_TAG);
                mCurrentConfigType = ConfigurationTypes.getByInternalName(type);

                mCurrentAddValues = new HashMap<String, String>();
                mResolvableProperties = new ArrayList<ResolvableProperty>();
            }
            else if (!mOldFileFormat && XMLTags.ADDITIONAL_DATA_TAG.equals(qName)) {
                mCurrentAddValues.put(attributes.getValue(XMLTags.NAME_TAG), attributes
                        .getValue(XMLTags.VALUE_TAG));
            }
            else if (!mOldFileFormat && XMLTags.PROPERTY_TAG.equals(qName)) {

                String name = attributes.getValue(XMLTags.NAME_TAG);
                String value = attributes.getValue(XMLTags.VALUE_TAG);

                ResolvableProperty prop = new ResolvableProperty(name, value);
                mResolvableProperties.add(prop);
            }
        }

        /**
         * {@inheritDoc}
         */
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (!mOldFileFormat && XMLTags.CHECK_CONFIG_TAG.equals(qName)) {
                try {

                    ICheckConfiguration checkConfig = new CheckConfiguration(mCurrentName,
                            mCurrentLocation, mCurrentDescription, mCurrentConfigType, true,
                            mResolvableProperties, mCurrentAddValues);
                    mConfigurations.add(checkConfig);

                }
                catch (Exception e) {
                    throw new SAXException(e);
                }
            }
        }
    }
}