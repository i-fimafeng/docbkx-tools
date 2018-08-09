/*
 * #%L
 * Docbkx Maven Plugin
 * %%
 * Copyright (C) 2006 - 2014 Wilfred Springer, Cedric Pronzato
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.agilejava.docbkx.maven;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import org.antlr.stringtemplate.StringTemplate;
import org.antlr.stringtemplate.StringTemplateGroup;

import org.apache.avalon.framework.configuration.Configuration;
import org.apache.avalon.framework.configuration.ConfigurationException;
import org.apache.avalon.framework.configuration.DefaultConfigurationBuilder;

import org.apache.commons.io.IOUtils;

import org.apache.commons.logging.LogFactory;
import org.apache.fop.apps.FOPException;
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.MimeConstants;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import org.apache.log4j.PatternLayout;
import org.apache.maven.plugin.MojoExecutionException;

import org.codehaus.plexus.util.FileUtils;

import org.xml.sax.SAXException;

/**
 * A replacement base class, to be inherited by the FO building plugin. This base class will
 * generate PDF, RTF, ... from the FO output by overriding {@link #postProcessResult(File)}.
 *
 * @author Wilfred Springer
 */
public abstract class AbstractFoMojo extends AbstractMojoBase {
  private String baseUrl;

  /**
   * The fonts that should be taken into account. (Without this parameter, the PDF document
   * will only be able to reference the default fonts.)
   *
   * @parameter
   */
  private Font[] fonts;

  /**
   * Resolution in dpi (dots per inch) used to specify the output resolution for bitmap
   * images generated by bitmap renderers (such as the TIFF renderer) and by bitmaps generated by
   * Apache Batik for filter effects and such
   *
   * @parameter default-value="0"
   */
  int targetResolution;

  /**
   * Resolution in dpi (dots per inch) which is used internally to determine the pixel size
   * for SVG images and bitmap images without resolution information.
   *
   * @parameter default-value="0"
   */
  int sourceResolution;


  /**
   * Author name including in PDF metadata.
   *
   * @parameter default-value="0"
   */
  String author;
  
  /**
   * Points to the an external FOP configuration file (eg fop.xconf). The use of this
   * parameter will disable the use of maven inline FOP configuration.
   *
   * @parameter
   */
  File externalFOPConfiguration = null;

  /**
   * Configures the loglevel of fop and xmlgraphics that can be really noisy.
   * Values are: OFF,FATAL,ERROR,WARN,INFO,DEBUG,TRACE,ALL
   *
   * @parameter property="docbkx.fopLogLevel" default-value="WARN" 
   */
  String fopLogLevel = null;

  private String currentFileExtension;

  /**
   * DOCUMENT ME!
   *
   * @throws MojoExecutionException DOCUMENT ME!
   */
  public void preProcess() throws MojoExecutionException {
    super.preProcess();
    // as this FO output is a transformation in 2 phases (XML to FO) and (FO to targetFileExtension)
    // we need to set the targetFileExtension to FO for the parent class and restore the
    // expected targetFileExtension later.
    currentFileExtension = getTargetFileExtension();
    setTargetFileExtension(getType());

    configureLog();
  }

  protected void configureLog() {
    Logger rootLogger = Logger.getRootLogger();
    if (!rootLogger.getAllAppenders().hasMoreElements()) {
      // configure a default logger if there is no previous configuration
      rootLogger.setLevel(Level.WARN);
      rootLogger.addAppender(new ConsoleAppender(
          new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN)));
      }

    // then configure loggers for fop and xmlgraphics
    Logger fopLogger = rootLogger.getLoggerRepository().getLogger("org.apache.fop");
    fopLogger.setLevel(Level.toLevel(fopLogLevel));
    Logger xmlgraphicsLogger = rootLogger.getLoggerRepository().getLogger("org.apache.xmlgraphics");
    xmlgraphicsLogger.setLevel(Level.toLevel(fopLogLevel));
  }

  /**
   * DOCUMENT ME!
   *
   * @param result DOCUMENT ME!
   *
   * @throws MojoExecutionException DOCUMENT ME!
   */
  public void postProcessResult(File result) throws MojoExecutionException {
    super.postProcessResult(result);

    // FOUserAgent can be used to set PDF metadata
    Configuration configuration = loadFOPConfig();
    InputStream in = null;
    OutputStream out = null;

    try {
      in = openFileForInput(result);

      final File outputFile = getOutputFile(result);
      out = openFileForOutput(outputFile);

      FopFactoryBuilder builder = new FopFactoryBuilder(new URI(baseUrl)).setConfiguration(configuration);
      						//building the factory with the user options
      FopFactory fopFactory = builder.build();
      final FOUserAgent userAgent = fopFactory.newFOUserAgent();
      userAgent.setAuthor(author);
      
      Fop fop = fopFactory.newFop(getMimeType(), out);

      // Setup JAXP using identity transformer
      TransformerFactory factory = TransformerFactory.newInstance();
      Transformer transformer = factory.newTransformer(); // identity transformer

      // Setup input stream
      Source src = new StreamSource(in);

      // Resulting SAX events (the generated FO) must be piped through to FOP
      Result res = new SAXResult(fop.getDefaultHandler());

      // Start XSLT transformation and FOP processing
      transformer.transform(src, res);
      getLog().info(outputFile.getAbsolutePath() + " has been generated.");
    } catch (FOPException e) {
      throw new MojoExecutionException("Failed to convert to " + getTargetFileExtension(), e);
    } catch (TransformerConfigurationException e) {
      throw new MojoExecutionException("Failed to load JAXP configuration", e);
    } catch (TransformerException e) {
      throw new MojoExecutionException("Failed to transform to " + getTargetFileExtension(), e);
    } catch (URISyntaxException e) {
      throw new MojoExecutionException("Failed to decode baseURL " + baseUrl, e);
	} finally {
      IOUtils.closeQuietly(out);
      IOUtils.closeQuietly(in);
    }
  }

  private InputStream openFileForInput(File file) throws MojoExecutionException {
    try {
      return new FileInputStream(file);
    } catch (FileNotFoundException fnfe) {
      throw new MojoExecutionException("Failed to open " + file + " for input.");
    }
  }

  private File getOutputFile(File inputFile) {
    String basename = FileUtils.basename(inputFile.getAbsolutePath());

    return new File(getTargetDirectory(), basename + currentFileExtension);
  }

  private OutputStream openFileForOutput(File file) throws MojoExecutionException {
    try {
      return new BufferedOutputStream(new FileOutputStream(file));
    } catch (FileNotFoundException fnfe) {
      throw new MojoExecutionException("Failed to open " + file + " for output.");
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @return DOCUMENT ME!
   */
  protected String getMimeType() {
    getLog().info("targetFileExtension " + currentFileExtension);
    getLog().info("type " + getType());

    if ("rtf".equals(currentFileExtension)) {
      return MimeConstants.MIME_RTF;
    }

    // return as default for now
    return MimeConstants.MIME_PDF;
  }

  /**
   * DOCUMENT ME!
   *
   * @return DOCUMENT ME!
   *
   * @throws MojoExecutionException DOCUMENT ME!
   */
  protected Configuration loadFOPConfig() throws MojoExecutionException {
    // if using external fop configuration file
    if (externalFOPConfiguration != null) {
      DefaultConfigurationBuilder builder = new DefaultConfigurationBuilder();

      try {
        if (getLog().isDebugEnabled())
          getLog().debug("Using external FOP configuration file: " + externalFOPConfiguration.toString());

        getLog().info("Ignoring pom inline FOP configuration");

        return builder.buildFromFile(externalFOPConfiguration);
      } catch (IOException ioe) {
        throw new MojoExecutionException("Failed to load external FOP config.", ioe);
      } catch (SAXException saxe) {
        throw new MojoExecutionException("Failed to parse external FOP config.", saxe);
      } catch (ConfigurationException e) {
        throw new MojoExecutionException("Failed to do something Avalon requires....", e);
      }

      // else generating the configuration file
    } else {
      ClassLoader loader = this.getClass().getClassLoader();
      InputStream in = loader.getResourceAsStream("fonts.stg");
      Reader reader = new InputStreamReader(in);
      StringTemplateGroup group = new StringTemplateGroup(reader);
      StringTemplate template = group.getInstanceOf("config");
      template.setAttribute("fonts", fonts);

      if (targetResolution != 0) {
        template.setAttribute("targetResolution", targetResolution);
      }

      if (sourceResolution != 0) {
        template.setAttribute("sourceResolution", sourceResolution);
      }

      DefaultConfigurationBuilder builder = new DefaultConfigurationBuilder();
      final String config = template.toString();

      if (getLog().isDebugEnabled()) {
        getLog().debug(config);
      }

      try {
        return builder.build(IOUtils.toInputStream(config));
      } catch (IOException ioe) {
        throw new MojoExecutionException("Failed to load FOP config.", ioe);
      } catch (SAXException saxe) {
        throw new MojoExecutionException("Failed to parse FOP config.", saxe);
      } catch (ConfigurationException e) {
        throw new MojoExecutionException("Failed to do something Avalon requires....", e);
      }
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @param transformer DOCUMENT ME!
   * @param sourceFilename DOCUMENT ME!
   * @param targetFile DOCUMENT ME!
   */
  public void adjustTransformer(Transformer transformer, String sourceFilename, File targetFile) {
    super.adjustTransformer(transformer, sourceFilename, targetFile);

    try {
      final String str = (new File(sourceFilename)).getParentFile().toURL().toExternalForm();
      baseUrl = str.replace("file:/", "file:///");
    } catch (MalformedURLException e) {
      getLog().warn("Failed to get FO basedir", e);
    }
  }
}
