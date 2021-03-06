package org.visallo.ldap;

import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import org.visallo.core.exception.VisalloException;
import com.google.inject.Singleton;
import com.unboundid.ldap.sdk.*;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustStoreTrustManager;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.apache.commons.lang.text.StrSubstitutor;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLSocketFactory;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.*;

@Singleton
public class LdapSearchServiceImpl implements LdapSearchService {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(LdapSearchServiceImpl.class);
    private LDAPConnectionPool pool;
    private LdapSearchConfiguration ldapSearchConfiguration;

    public LdapSearchServiceImpl(LdapServerConfiguration serverConfig, LdapSearchConfiguration searchConfig) throws GeneralSecurityException, LDAPException {
        TrustStoreTrustManager tsManager = new TrustStoreTrustManager(
                serverConfig.getTrustStore(),
                serverConfig.getTrustStorePassword().toCharArray(),
                serverConfig.getTrustStoreType(),
                true
        );
        SSLUtil sslUtil = new SSLUtil(tsManager);

        switch (serverConfig.getConnectionType()) {
            case LDAPS:
                LOGGER.info("creating ldap connection pool with LDAPS");
                SSLSocketFactory socketFactory = sslUtil.createSSLSocketFactory();

                if (serverConfig.getFailoverLdapServerHostname() != null) {
                    String[] addresses = {serverConfig.getPrimaryLdapServerHostname(), serverConfig.getFailoverLdapServerHostname()};
                    int[] ports = {serverConfig.getPrimaryLdapServerPort(), serverConfig.getFailoverLdapServerPort()};
                    FailoverServerSet failoverSet = new FailoverServerSet(addresses, ports, socketFactory);
                    SimpleBindRequest bindRequest = new SimpleBindRequest(serverConfig.getBindDn(), serverConfig.getBindPassword());
                    pool = new LDAPConnectionPool(failoverSet, bindRequest, serverConfig.getMaxConnections());
                } else {
                    LDAPConnection ldapConnection = new LDAPConnection(
                            socketFactory,
                            serverConfig.getPrimaryLdapServerHostname(),
                            serverConfig.getPrimaryLdapServerPort(),
                            serverConfig.getBindDn(),
                            serverConfig.getBindPassword()
                    );
                    pool = new LDAPConnectionPool(ldapConnection, serverConfig.getMaxConnections());
                }

                break;
            case STARTTLS:
                LOGGER.info("creating ldap connection pool with StartTLS");
                StartTLSPostConnectProcessor startTLSProcessor = new StartTLSPostConnectProcessor(sslUtil.createSSLContext());

                if (serverConfig.getFailoverLdapServerHostname() != null) {
                    String[] addresses = {serverConfig.getPrimaryLdapServerHostname(), serverConfig.getFailoverLdapServerHostname()};
                    int[] ports = {serverConfig.getPrimaryLdapServerPort(), serverConfig.getFailoverLdapServerPort()};
                    FailoverServerSet failoverSet = new FailoverServerSet(addresses, ports);
                    SimpleBindRequest bindRequest = new SimpleBindRequest(serverConfig.getBindDn(), serverConfig.getBindPassword());
                    pool = new LDAPConnectionPool(failoverSet, bindRequest, 1, serverConfig.getMaxConnections(), startTLSProcessor);
                } else {
                    LDAPConnection ldapConnection = new LDAPConnection(
                            serverConfig.getPrimaryLdapServerHostname(),
                            serverConfig.getPrimaryLdapServerPort()
                    );
                    ldapConnection.processExtendedOperation(new StartTLSExtendedRequest(sslUtil.createSSLContext()));
                    ldapConnection.bind(
                            serverConfig.getBindDn(),
                            serverConfig.getBindPassword()
                    );
                    pool = new LDAPConnectionPool(ldapConnection, 1, serverConfig.getMaxConnections(), startTLSProcessor);
                }

                break;
            default:
                throw new VisalloException("Unexpected connection type: " + serverConfig.getConnectionType());
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (!pool.isClosed()) {
                    LOGGER.info("closing ldap connection pool");
                    pool.close();
                }
            }
        });

        ldapSearchConfiguration = searchConfig;
    }

    @Override
    public SearchResultEntry searchPeople(X509Certificate certificate) {
        Filter filter = buildPeopleSearchFilter(certificate);

        List<String> attributeNames = new ArrayList<String>(ldapSearchConfiguration.getUserAttributes());
        if (certificate != null) {
            attributeNames.add(ldapSearchConfiguration.getUserCertificateAttribute());
        }

        SearchResult results;
        try {
            results = pool.search(
                    ldapSearchConfiguration.getUserSearchBase(),
                    ldapSearchConfiguration.getUserSearchScope(),
                    filter,
                    attributeNames.toArray(new String[attributeNames.size()])
            );
        } catch (LDAPSearchException lse) {
            if (lse.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                throw new VisalloException("no results for LDAP search: " + filter, lse);
            }
            throw new VisalloException("search failed", lse);
        }

        if (results.getEntryCount() == 0) {
            throw new VisalloException("no results for LDAP search: " + filter);
        }

        if (certificate != null) {
            return getMatchingSearchResultEntry(certificate, filter, results);
        } else {
            if (results.getEntryCount() > 1) {
                throw new VisalloException("certificate matching not requested and more than one result for LDAP search: " + filter);
            }
            return results.getSearchEntries().get(0);
        }
    }

    @Override
    public Set<String> searchGroups(SearchResultEntry personEntry) {
        Map<String, String> subs = new HashMap<String, String>();
        subs.put("dn", personEntry.getDN());
        for (Attribute attr : personEntry.getAttributes()) {
            subs.put(attr.getName(), attr.getValue());
        }

        try {
            StrSubstitutor sub = new StrSubstitutor(subs);
            String filterStr = sub.replace(ldapSearchConfiguration.getGroupSearchFilter());
            Filter filter = Filter.create(filterStr);
            SearchResult results = pool.search(
                    ldapSearchConfiguration.getGroupSearchBase(),
                    ldapSearchConfiguration.getGroupSearchScope(),
                    filter,
                    ldapSearchConfiguration.getGroupNameAttribute()
            );

            Set<String> groupNames = new HashSet<String>();
            for (SearchResultEntry entry : results.getSearchEntries()) {
                if (entry.hasAttribute(ldapSearchConfiguration.getGroupNameAttribute())) {
                    groupNames.add(entry.getAttributeValue(ldapSearchConfiguration.getGroupNameAttribute()));
                }
            }

            return groupNames;
        } catch (LDAPSearchException e) {
            throw new VisalloException("search failed", e);
        } catch (LDAPException e) {
            throw new VisalloException("Could not create filter", e);
        }
    }

    private Filter buildPeopleSearchFilter(X509Certificate certificate) {
        String certSubjectName = certificate.getSubjectX500Principal().getName();
        try {
            LdapName ldapDN = new LdapName(certSubjectName);
            Map<String, Object> subs = new HashMap<String, Object>();
            for (Rdn rdn : ldapDN.getRdns()) {
                subs.put(rdn.getType().toLowerCase(), rdn.getValue());
            }
            StrSubstitutor sub = new StrSubstitutor(subs);
            String filterStr = sub.replace(ldapSearchConfiguration.getUserSearchFilter());
            return Filter.create(filterStr);
        } catch (InvalidNameException e) {
            throw new VisalloException("invalid certificate subject name: " + certSubjectName, e);
        } catch (LDAPException e) {
            throw new VisalloException("Could not create filter", e);
        }
    }

    private SearchResultEntry getMatchingSearchResultEntry(X509Certificate certificate, Filter filter, SearchResult results) {
        byte[] encodedCert = new byte[0];
        try {
            encodedCert = certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new VisalloException("unable to get encoded version of user certificate", e);
        }

        for (SearchResultEntry entry : results.getSearchEntries()) {
            byte[][] entryCertificates = entry.getAttributeValueByteArrays(ldapSearchConfiguration.getUserCertificateAttribute());
            for (byte[] entryCertificate : entryCertificates) {
                if (Arrays.equals(entryCertificate, encodedCert)) {
                    return entry;
                }
            }
        }

        throw new VisalloException("no results with matching certificate for LDAP search: " + filter);
    }
}
