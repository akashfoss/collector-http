/* Copyright 2010-2015 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.collector.http.robot.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.collector.http.robot.IRobotsMetaProvider;
import com.norconex.collector.http.robot.RobotsMeta;
import com.norconex.commons.lang.config.XMLConfigurationUtil;
import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.Properties;

/**
 * <p>Implementation of {@link IRobotsMetaProvider} as per X-Robots-Tag 
 * and ROBOTS standards. 
 * Extracts robots information from "ROBOTS" meta tag in an HTML page
 * or "X-Robots-Tag" tag in the HTTP header (see
 * <a href="https://developers.google.com/webmasters/control-crawl-index/docs/robots_meta_tag">
 * https://developers.google.com/webmasters/control-crawl-index/docs/robots_meta_tag</a>
 * and 
 * <a href="http://www.robotstxt.org/meta.html">
 * http://www.robotstxt.org/meta.html</a>).
 * </p>
 * 
 * <p>If you specified a prefix for the HTTP headers, make sure to specify it 
 * again here or the robots meta tags will not be found.</p>
 * 
 * <p>If robots instructions are provided in both the HTML page and 
 * HTTP header, the ones in HTML page will take precedence, and the
 * ones in HTTP header will be ignored.</p>
 * 
 * <p>
 * XML configuration usage:
 * </p>
 * <pre>
 *  &lt;robotsMeta ignore="false" 
 *     class="com.norconex.collector.http.robot.StandardRobotsMetaProvider"&gt;
 *     &lt;headersPrefix&gt;(string prefixing headers)&lt;/headersPrefix&gt;
 *  &lt;/robotsMeta&gt;
 * </pre>
 * @author Pascal Essiembre
 */
public class StandardRobotsMetaProvider 
        implements IRobotsMetaProvider, IXMLConfigurable {

    private static final Logger LOG = LogManager.getLogger(
            StandardRobotsMetaProvider.class);
    private static final Pattern META_ROBOTS_PATTERN = Pattern.compile(
            "<\\s*META[^>]*?NAME\\s*=\\s*[\"']{0,1}\\s*robots"
                    + "\\s*[\"']{0,1}\\s*[^>]*?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_CONTENT_PATTERN = Pattern.compile(
            "\\s*CONTENT\\s*=\\s*[\"']{0,1}([\\s\\w,]+)[\"']{0,1}\\s*[^>]*?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEAD_PATTERN = Pattern.compile(
            "<\\s*/\\s*HEAD\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    
    private String headersPrefix;

    @Override
    public RobotsMeta getRobotsMeta(Reader document, String documentUrl,
           ContentType contentType, Properties httpHeaders) throws IOException {

        RobotsMeta robotsMeta = null;
        
        //--- Find in page content ---
        if (isMetaSupportingContentType(contentType)) {
            TextReader reader = new TextReader(document);
            String text = null;
            while ((text = reader.readText()) != null) {
                // First eliminate comments
                String clean = COMMENT_PATTERN.matcher(text).replaceAll("");
                String robotContent = findInContent(clean);
                if (robotContent != null) {
                    robotsMeta = buildMeta(robotContent);
                    if (LOG.isDebugEnabled() && robotsMeta != null) {
                        LOG.debug("Meta robots \"" + robotContent 
                             + "\" found in HTML meta tag for: " + documentUrl);
                    }
                    break;
                } else if (isEndOfHead(clean)) {
                    break;
                }
            }
            reader.close();
        }
        
        //--- Find in HTTP header ---
        if (robotsMeta == null) {
            robotsMeta = findInHeaders(httpHeaders, documentUrl);
        }

        if (LOG.isDebugEnabled() && robotsMeta == null) {
            LOG.debug("No meta robots found for: " + documentUrl);
        }
        
        return robotsMeta;
    }
    
    public String getHeadersPrefix() {
        return headersPrefix;
    }
    public void setHeadersPrefix(String headersPrefix) {
        this.headersPrefix = headersPrefix;
    }

    private boolean isMetaSupportingContentType(ContentType contentType) {
        return contentType != null && contentType.equals(ContentType.HTML);
    }
    
    private RobotsMeta findInHeaders(
            Properties httpHeaders, String documentUrl) {
        String name = "X-Robots-Tag";
        if (StringUtils.isNotBlank(headersPrefix)) {
            name = headersPrefix + name;
        }
        String content = httpHeaders.getString(name);
        RobotsMeta robotsMeta = buildMeta(content);
        if (LOG.isDebugEnabled() && robotsMeta != null) {
            LOG.debug("Meta robots \"" + content 
                    + "\" found in HTTP header for: " + documentUrl);
        }
        return robotsMeta;
    }
    
    private RobotsMeta buildMeta(String content) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        String[] rules = StringUtils.split(content, ',');
        boolean noindex = false;
        boolean nofollow = false;
        for (String rule : rules) {
            if (rule.trim().equalsIgnoreCase("noindex")) {
                noindex = true;
            }
            if (rule.trim().equalsIgnoreCase("nofollow")) {
                nofollow = true;
            }
        }
        return new RobotsMeta(nofollow, noindex);
    }
    
    
    private String findInContent(String text) {
        Matcher rmatcher = META_ROBOTS_PATTERN.matcher(text);
        while (rmatcher.find()) {
            String robotTag = rmatcher.group();
            Matcher cmatcher = META_CONTENT_PATTERN.matcher(robotTag);
            if (cmatcher.find()) {
                String content = cmatcher.group(1);
                if (StringUtils.isNotBlank(content)) {
                    return content;
                }
            }
        }
        return null;
    }
    
    private boolean isEndOfHead(String line) {
        return HEAD_PATTERN.matcher(line).matches();
    }

    @Override
    public void loadFromXML(Reader in) throws IOException {
        XMLConfiguration xml = XMLConfigurationUtil.newXMLConfiguration(in);
        setHeadersPrefix(xml.getString("headersPrefix", null));
    }

    @Override
    public void saveToXML(Writer out) throws IOException {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        try {
            XMLStreamWriter writer = factory.createXMLStreamWriter(out);
            writer.writeStartElement("robotsMeta");
            writer.writeAttribute("class", getClass().getCanonicalName());
            if (headersPrefix != null) {
                writer.writeStartElement("headersPrefix");
                writer.writeCharacters(headersPrefix);
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Cannot save as XML.", e);
        }
    }
    
    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof StandardRobotsMetaProvider)) {
            return false;
        }
        StandardRobotsMetaProvider castOther = 
                (StandardRobotsMetaProvider) other;
        return new EqualsBuilder()
                .append(headersPrefix, castOther.headersPrefix)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(headersPrefix)
                .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("headersPrefix", headersPrefix)
                .toString();
    }    
}
