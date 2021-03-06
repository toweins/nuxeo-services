/*
 * (C) Copyright 2009 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Anahide Tchertchian
 */

package org.nuxeo.ecm.directory.ldap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.StringUtils;
import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.ecm.directory.AbstractReference;
import org.nuxeo.ecm.directory.Directory;
import org.nuxeo.ecm.directory.DirectoryException;

/**
 * Implementation of the directory Reference interface that makes it possible
 * to retrieve children of a node in the LDAP tree structure.
 *
 * @author Anahide Tchertchian
 */
@XObject(value = "ldapTreeReference")
public class LDAPTreeReference extends AbstractReference {

    private static final Log log = LogFactory.getLog(LDAPTreeReference.class);

    public static final List<String> EMPTY_STRING_LIST = Collections.emptyList();

    protected LDAPDirectoryDescriptor targetDirectoryDescriptor;

    protected int scope;

    @XNode("@field")
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    protected LDAPFilterMatcher getFilterMatcher() {
        return new LDAPFilterMatcher();
    }

    @Override
    @XNode("@directory")
    public void setTargetDirectoryName(String targetDirectoryName) {
        this.targetDirectoryName = targetDirectoryName;
    }

    public int getScope() {
        return scope;
    }

    @XNode("@scope")
    public void setScope(String scope) throws DirectoryException {
        if (scope == null) {
            // default value: onelevel
            this.scope = SearchControls.ONELEVEL_SCOPE;
            return;
        }
        Integer searchScope = LdapScope.getIntegerScope(scope);
        if (searchScope == null) {
            // invalid scope
            throw new DirectoryException("Invalid search scope: " + scope
                    + ". Valid options: object, onelevel, subtree");
        }
        this.scope = searchScope.intValue();
    }

    @Override
    public Directory getSourceDirectory() throws DirectoryException {

        Directory sourceDir = super.getSourceDirectory();
        if (sourceDir instanceof LDAPDirectoryProxy) {
            return ((LDAPDirectoryProxy) sourceDir).getDirectory();
        } else {
            throw new DirectoryException(
                    sourceDirectoryName
                            + " is not a LDAPDirectory and thus cannot be used in a reference for "
                            + fieldName);
        }
    }

    @Override
    public Directory getTargetDirectory() throws DirectoryException {
        Directory targetDir = super.getTargetDirectory();
        if (targetDir instanceof LDAPDirectoryProxy) {
            return ((LDAPDirectoryProxy) targetDir).getDirectory();
        } else {
            throw new DirectoryException(
                    targetDirectoryName
                            + " is not a LDAPDirectory and thus cannot be referenced as target by "
                            + fieldName);
        }
    }

    protected LDAPDirectory getTargetLDAPDirectory() throws DirectoryException {
        return (LDAPDirectory) getTargetDirectory();
    }

    protected LDAPDirectory getSourceLDAPDirectory() throws DirectoryException {
        return (LDAPDirectory) getSourceDirectory();
    }

    protected LDAPDirectoryDescriptor getTargetDirectoryDescriptor()
            throws DirectoryException {
        if (targetDirectoryDescriptor == null) {
            targetDirectoryDescriptor = getTargetLDAPDirectory().getConfig();
        }
        return targetDirectoryDescriptor;
    }

    /**
     * NOT IMPLEMENTED: Store new links
     *
     * @see org.nuxeo.ecm.directory.Reference#addLinks(String, List)
     */
    public void addLinks(String sourceId, List<String> targetIds)
            throws DirectoryException {
        // TODO: not yet implemented
    }

    /**
     * NOT IMPLEMENTED: Store new links.
     *
     * @see org.nuxeo.ecm.directory.Reference#addLinks(List, String)
     */
    public void addLinks(List<String> sourceIds, String targetId)
            throws DirectoryException {
        // TODO: not yet implemented
    }

    /**
     * Fetches single parent, cutting the dn and trying to get the given entry.
     *
     * @see org.nuxeo.ecm.directory.Reference#getSourceIdsForTarget(String)
     */
    public List<String> getSourceIdsForTarget(String targetId)
            throws DirectoryException {
        Set<String> sourceIds = new TreeSet<String>();
        String targetDn = null;

        // step #1: fetch the dn of the targetId entry in the target
        // directory by the static dn valued strategy
        LDAPDirectory targetDir = getTargetLDAPDirectory();
        LDAPSession targetSession = (LDAPSession) targetDir.getSession();
        try {
            SearchResult targetLdapEntry = targetSession.getLdapEntry(targetId,
                    true);
            if (targetLdapEntry == null) {
                // no parent accessible => return empty list
                return EMPTY_STRING_LIST;
            }
            targetDn = pseudoNormalizeDn(targetLdapEntry.getNameInNamespace());
        } catch (NamingException e) {
            throw new DirectoryException("error fetching " + targetId, e);
        } finally {
            targetSession.close();
        }

        // step #2: search for entries that reference parent dn in the
        // source directory and collect its id
        LDAPDirectory sourceDirectory = getSourceLDAPDirectory();
        String parentDn = getParentDn(targetDn);
        String filterExpr = String.format("(&%s)",
                sourceDirectory.getBaseFilter());
        String[] filterArgs = {};

        LDAPSession sourceSession = (LDAPSession) sourceDirectory.getSession();
        // get a copy of original search controls
        SearchControls sctls = sourceDirectory.getSearchControls(true);
        sctls.setSearchScope(SearchControls.OBJECT_SCOPE);
        try {
            if (log.isDebugEnabled()) {
                log.debug(String.format(
                        "LDAPReference.getSourceIdsForTarget(%s): LDAP search search base='%s'"
                                + " filter='%s' args='%s' scope='%s' [%s]",
                        targetId, parentDn, filterExpr,
                        StringUtils.join(filterArgs, ", "),
                        sctls.getSearchScope(), this));
            }
            NamingEnumeration<SearchResult> results = sourceSession.dirContext.search(
                    parentDn, filterExpr, filterArgs, sctls);

            try {
                while (results.hasMore()) {
                    Attributes attributes = results.next().getAttributes();
                    // NXP-2461: check that id field is filled
                    Attribute attr = attributes.get(sourceSession.idAttribute);
                    if (attr != null) {
                        Object value = attr.get();
                        if (value != null) {
                            sourceIds.add(value.toString());
                            // only supposed to get one result anyway
                            break;
                        }
                    }
                }
            } finally {
                results.close();
            }
        } catch (NamingException e) {
            throw new DirectoryException("error during reference search for "
                    + targetDn, e);
        } finally {
            sourceSession.close();
        }

        return new ArrayList<String>(sourceIds);
    }

    /**
     * Fetches children, onelevel or subtree given the reference configuration.
     * <p>
     * Removes entries with same id than parent to only get real children.
     *
     * @see org.nuxeo.ecm.directory.Reference#getTargetIdsForSource(String)
     */
    // TODO: optimize reusing the same ldap session (see LdapReference optim
    // method)
    public List<String> getTargetIdsForSource(String sourceId)
            throws DirectoryException {
        Set<String> targetIds = new TreeSet<String>();
        String sourceDn = null;

        // step #1: fetch the dn of the sourceId entry in the source
        // directory by the static dn valued strategy
        LDAPDirectory sourceDir = getSourceLDAPDirectory();
        LDAPSession sourceSession = (LDAPSession) sourceDir.getSession();
        try {
            SearchResult sourceLdapEntry = sourceSession.getLdapEntry(sourceId,
                    true);
            if (sourceLdapEntry == null) {
                throw new DirectoryException(sourceId + " does not exist in "
                        + sourceDirectoryName);
            }
            sourceDn = pseudoNormalizeDn(sourceLdapEntry.getNameInNamespace());
        } catch (NamingException e) {
            throw new DirectoryException("error fetching " + sourceId, e);
        } finally {
            sourceSession.close();
        }

        // step #2: search for entries with sourceDn as base dn and collect
        // their ids
        LDAPDirectory targetDirectory = getTargetLDAPDirectory();

        String filterExpr = String.format("(&%s)",
                targetDirectory.getBaseFilter());
        String[] filterArgs = {};

        LDAPSession targetSession = (LDAPSession) targetDirectory.getSession();
        // get a copy of original search controls
        SearchControls sctls = targetDirectory.getSearchControls(true);
        sctls.setSearchScope(getScope());
        try {
            if (log.isDebugEnabled()) {
                log.debug(String.format(
                        "LDAPReference.getTargetIdsForSource(%s): LDAP search search base='%s'"
                                + " filter='%s' args='%s' scope='%s' [%s]",
                        sourceId, sourceDn, filterExpr,
                        StringUtils.join(filterArgs, ", "),
                        sctls.getSearchScope(), this));
            }
            NamingEnumeration<SearchResult> results = targetSession.dirContext.search(
                    sourceDn, filterExpr, filterArgs, sctls);

            try {
                while (results.hasMore()) {
                    Attributes attributes = results.next().getAttributes();
                    // NXP-2461: check that id field is filled
                    Attribute attr = attributes.get(targetSession.idAttribute);
                    if (attr != null) {
                        Object value = attr.get();
                        if (value != null) {
                            // always remove self as child
                            String targetId = value.toString();
                            if (!sourceId.equals(targetId)) {
                                targetIds.add(targetId);
                            }
                        }
                    }
                }
            } finally {
                results.close();
            }
        } catch (NamingException e) {
            throw new DirectoryException("error during reference search for "
                    + sourceDn, e);
        } finally {
            targetSession.close();
        }

        return new ArrayList<String>(targetIds);
    }

    /**
     * Simple helper that replaces ", " by "," in the provided dn and returns
     * the lower case version of the result for comparison purpose.
     *
     * @param dn the raw unnormalized dn
     * @return lowercase version without whitespace after commas
     */
    protected static String pseudoNormalizeDn(String dn)
            throws InvalidNameException {
        LdapName ldapName = new LdapName(dn);
        List<String> rdns = new ArrayList<String>();
        for (Rdn rdn : ldapName.getRdns()) {
            String value = rdn.getValue().toString().toLowerCase().replaceAll(
                    ",", "\\\\,");
            String rdnStr = rdn.getType().toLowerCase() + "=" + value;
            rdns.add(0, rdnStr);
        }
        return StringUtils.join(rdns, ',');
    }

    protected String getParentDn(String dn) {
        LdapName ldapName;
        String parentDn;

        if (dn != null) {
            try {
                ldapName = new LdapName(dn);
                ldapName.remove(ldapName.size() - 1);
                parentDn = ldapName.toString();
                return parentDn;

            } catch (InvalidNameException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * NOT IMPLEMENTED: Remove existing statically defined links for the given
     * source id
     *
     * @see org.nuxeo.ecm.directory.Reference#removeLinksForSource(String)
     */
    public void removeLinksForSource(String sourceId) throws DirectoryException {
        // TODO: not yet implemented
    }

    /**
     * NOT IMPLEMENTED: Remove existing statically defined links for the given
     * target id
     *
     * @see org.nuxeo.ecm.directory.Reference#removeLinksForTarget(String)
     */
    public void removeLinksForTarget(String targetId) throws DirectoryException {
        // TODO: not yet implemented
    }

    /**
     * NOT IMPLEMENTED: Edit the list of statically defined references for a
     * given target
     *
     * @see org.nuxeo.ecm.directory.Reference#setSourceIdsForTarget(String,
     *      List)
     */
    public void setSourceIdsForTarget(String targetId, List<String> sourceIds)
            throws DirectoryException {
        // TODO: not yet implemented
    }

    /**
     * NOT IMPLEMENTED: Set the list of statically defined references for a
     * given source
     *
     * @see org.nuxeo.ecm.directory.Reference#setTargetIdsForSource(String,
     *      List)
     */
    public void setTargetIdsForSource(String sourceId, List<String> targetIds)
            throws DirectoryException {
        // TODO: not yet implemented
    }

    @Override
    // to build helpful debug logs
    public String toString() {
        return String.format(
                "LDAPTreeReference to resolve field='%s' of sourceDirectory='%s'"
                        + " with targetDirectory='%s'", fieldName,
                sourceDirectoryName, targetDirectoryName);
    }

    @Override
    protected AbstractReference newInstance() {
        return new LDAPTreeReference();
    }

    /**
     * @since 5.6
     */
    @Override
    public LDAPTreeReference clone() {
        LDAPTreeReference clone = (LDAPTreeReference) super.clone();
        clone.scope = scope;
        return clone;
    }

}
