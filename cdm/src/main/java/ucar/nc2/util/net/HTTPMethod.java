/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package ucar.nc2.util.net;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

import net.jcip.annotations.NotThreadSafe;
import org.apache.http.*;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.*;
import org.apache.http.client.params.AllClientPNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import ucar.nc2.util.EscapeStrings;

import javax.print.URIException;


/**
 * HTTPMethod is the encapsulation of specific
 * kind of server request: GET, HEAD, POST, etc.
 * The general processing sequence is as follows.
 * <ol>
 * <li> Create an HTTPMethod object using one of the
 * methods of HTTPFactory (e.g. HTTPFactory.Get()).
 * <p/>
 * <li> Set parameters and headers of the returned HTTPMethod instance.
 * <p/>
 * <li> Invoke the execute() method to actually make
 * the request.
 * <p/>
 * <li> Extract response headers.
 * <p/>
 * <li> Extract any body of the response in one of several forms:
 * an Inputstream, a byte array, or a String.
 * <p/>
 * <li> Close the method.
 * </ol>
 * In practice, one one has an HTTPMethod instance, one can
 * repeat the steps 2-5. Of course this assumes that one is
 * doing the same kind of action (e.g. GET).
 * <p/>
 * The arguments to the factory method are as follows.
 * <ul>
 * <li> An HTTPSession instance (optional).
 * <p/>
 * <li> A URL.
 * </ul>
 * An HTTPMethod instance is assumed to be operating in the context
 * of an HTTPSession instance as specified by the session argument.
 * If not present, the HTTPMethod instance will create a session
 * that will be reclaimed when the method is closed
 * (see the discussion about one-shot operation below).
 * <p/>
 * Method URLs may be specified in any of three ways.
 * <ol>
 * <li> It may be inherited from the URL specified when
 * the session was created.
 * <p/>
 * <li> It may be specified as part of the HTTPMethod
 * constructor (via the factory). If none is specified,
 * then the session URL is used.
 * <p/>
 * <li> It may be specified as an argument to the
 * execute() method. If none is specified, then
 * the factory constructor URL is used (which might,
 * in turn have come from the session).
 * </ol>
 * <p/>
 * Legal url arguments to HTTPMethod are constrained by the URL
 * specified in creating the HTTPSession instance, if any.  If
 * the session was constructed with a specified URL, then any
 * url specified to HTTMethod (via the factory or via
 * execute()) must be "compatible" with the session URL). The
 * term "compatible" basically means that the session url, as a
 * string, must be a prefix of the specified method url.  This
 * maintains the semantics of the Session but allows
 * flexibility in accessing data from the server.
 * <p/>
 * As an example, the session url might be
 * "http://motherlode.ucar.edu" and the method url might be a
 * more specific URL such as
 * http://motherlode.ucar.edu/path/file.nc.dds.
 * <p/>
 * <u>One-Shot Operation:</u>
 * A reasonably common use case is when a client
 * wants to create a method, execute it, get the response,
 * and close the method. For this use case, creating a session
 * and making sure it gets closed can be a tricky proposition.
 * To support this use case, HTTPMethod supports what amounts
 * to a one-shot use. The steps are as follows:
 * <ol>
 * <li> HTTPMethod method = HTTPFactory.Get(<url string>); note
 * that this implicitly creates a session internal to the
 * method instance.
 * <p/>
 * <li> Set any session parameters or headers using method.getSession().setXXX
 * <p/>
 * <li> Set any parameters and headers on method
 * <p/>
 * <li> method.execute();
 * <p/>
 * <li> Get any response method headers
 * <p/>
 * <li> InputStream stream = method.getResponseBodyAsStream()
 * <p/>
 * <li> process the stream
 * <p/>
 * <li> stream.close()
 * </ol>
 * There are several things to note.
 * <ul>
 * <li> Closing the stream will close the underlying method, so it is not
 * necessary to call method.close().
 * <li> However, if you, for example, get the response body using getResponseBodyAsString(),
 * then you need to explicitly call method.close().
 * <li> Closing the method (directly or through stream.close())
 * will close the one-shot session created by the method.
 * </ul>
 */

@NotThreadSafe
public class HTTPMethod
{
    //////////////////////////////////////////////////////////////////////////
    // Static variables

    static HashMap<String, Object> globalparams = new HashMap<String, Object>();

    //////////////////////////////////////////////////
    // Static API

    static public synchronized void
    setGlobalParameter(String name, Object value)
    {
        globalparams.put(name, value);
    }

    //////////////////////////////////////////////////
    // Instance fields

    HTTPSession session = null;
    boolean localsession = false;
    String legalurl = null;
    List<Header> headers = new ArrayList<Header>();
    HashMap<String, Object> params = new HashMap<String, Object>();
    HttpContext context = null;
    HttpEntity content = null;
    HTTPSession.Methods methodclass = null;
    HTTPMethodStream methodstream = null; // wrapper for strm
    boolean closed = false;
    HttpRequestBase method = null;
    HttpResponse response = null;
    //todo: List<org.apache.http.entity.mime.FormBodyPart> multiparts = null;



    //////////////////////////////////////////////////
    // Constructor(s)

    public HTTPMethod(HTTPSession.Methods m)
        throws HTTPException
    {
        this(m, null, null);
    }

    public HTTPMethod(HTTPSession.Methods m, String url)
        throws HTTPException
    {
        this(m, null, url);
    }

    public HTTPMethod(HTTPSession.Methods m, HTTPSession session, String url)
        throws HTTPException
    {
        if(session == null) {
            session = HTTPFactory.newSession();
            localsession = true;
        }
        this.session = session;

        if(url == null)
            url = session.getURL();
        if(url != null)
            url = HTTPSession.removeprincipal(url);

        this.legalurl = url;
        this.session.addMethod(this);

        this.methodclass = m;
    }

    HttpRequestBase
    create()
    {
        HttpRequestBase method = null;
        // Unfortunately, the apache httpclient 3 code has a restrictive
        // notion of a legal url, so we need to encode it before use
        String urlencoded = EscapeStrings.escapeURL(this.legalurl);

        switch (this.methodclass) {
        case Put:
            method = new HttpPut(urlencoded);
            break;
        case Post:
            method = new HttpPost(urlencoded);
            break;
        case Get:
            method = new HttpGet(urlencoded);
            break;
        case Head:
            method = new HttpHead(urlencoded);
            break;
        case Options:
            method = new HttpOptions(urlencoded) ;
            break;
        default:
            break;
        }
        // Force some actions
        if(method != null) {
            method.getParams().setParameter(AllClientPNames.HANDLE_REDIRECTS,true);
            method.getParams().setParameter(AllClientPNames.HANDLE_AUTHENTICATION, true);
        }
        return method;
    }

    void setcontent()
    {
        switch (this.methodclass) {
        case Put:
            if(this.content != null)
                ((HttpPut)method).setEntity(this.content);
            break;
        case Post:
            //todo: if(multiparts != null && multiparts.length > 0) {
            //    MultipartEntity mre = new MultipartEntity();
            //    for()
            //    ((PostMethod) method).setRequestEntity(mre);
            //} else
            if(this.content != null)
                ((HttpPost) method).setEntity(this.content);
            break;
        case Head:
        case Get:
        case Options:
        default:
            break;
        }
        this.content = null; // do not reuse
        //todo: this.multiparts = null;
    }

    public int execute(String url) throws HTTPException
    {
        this.legalurl = url;
        return execute();
    }

    public int execute()
        throws HTTPException
    {
        if(closed)
            throw new HTTPException("HTTPMethod: attempt to execute closed method");
        if(this.legalurl == null)
            throw new HTTPException("HTTPMethod: no url specified");
        if(!localsession && !sessionCompatible(this.legalurl))
            throw new HTTPException("HTTPMethod: session incompatible url: " + this.legalurl);

        if(method != null)
            method.releaseConnection();
        method = create();

        try {
            if(headers.size() > 0) {
                for(Header h : headers) {
                    method.addHeader(h);
                }
            }
            if(globalparams != null) {
                HttpParams hmp = method.getParams();
                for(String key : globalparams.keySet()) {
                    hmp.setParameter(key, globalparams.get(key));
                }
            }
            if(params != null) {
                HttpParams hmp = method.getParams();
                for(String key : params.keySet()) {
                    hmp.setParameter(key, params.get(key));
                }
            }

            //todo: Change the retry handler
            //httpclient.setHttpRequestRetryHandler(myRetryHandler);
            //method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new RetryHandler());

            setcontent();

            setAuthentication(session, this);

            //todo: get the protocol and port
            //URL hack = new URL(this.legalurl);
            //Protocol handler = session.getProtocol(hack.getProtocol(),
            //    hack.getPort());
            //HostConfiguration hc = session.sessionClient.getHostConfiguration();
            //hc = new HostConfiguration(hc);
            //hc.setHost(hack.getHost(), hack.getPort(), handler);
            response = session.sessionClient.execute(method);
            int code = response.getStatusLine().getStatusCode();
            return code;
        } catch (Exception ie) {
            throw new HTTPException(ie);
        }
    }


    /**
     * Calling close will force the method to close, and will
     * force any open stream to terminate. If the session is local,
     * Then that too will be closed.
     */
    public synchronized void
    close()
    {
        if(closed)
            return; // multiple calls ok
        closed = true; // mark as closed to prevent recursive calls
        if(methodstream != null) {
            try {
                methodstream.close();
            } catch (IOException ioe) {/*failure is ok*/}
            ;
            methodstream = null;
        }
        if(this.method != null) {
            this.method.releaseConnection();
            this.method = null;
        }
        session.removeMethod(this);
        if(localsession && session != null) {
            session.close();
	        session = null;
	    }
    }

    //////////////////////////////////////////////////
    // Accessors

    public void setContext(HttpContext cxt)
    {
        session.setContext(cxt);
    }

    public HttpContext getContext()
    {
        return session.getContext();
    }

    public int getStatusCode()
    {
        return response == null ? 0 : response.getStatusLine().getStatusCode();
    }

    public String getStatusLine()
    {
        return response == null ? null : response.getStatusLine().toString();
    }

    public String getRequestLine()
    {
        //fix: return (method == null ? null : method.getRequestLine().toString());
        throw new UnsupportedOperationException("getrequestline not implemented");
    }

    public String getPath()
    {
        return (method == null ? null : method.getURI().toString());
    }

    public boolean canHoldContent()
    {
        if(method == null)
            return false;
        return !(method instanceof HttpHead);
    }

    public InputStream getResponseBodyAsStream()
    {
        return getResponseAsStream();
    }

    public InputStream getResponseAsStream()
    {
        if(closed)
            throw new IllegalStateException("HTTPMethod: method is closed");
        if(this.methodstream != null) { // duplicate: caller's problem
            HTTPSession.log.warn("HTTPMethod.getResponseBodyAsStream: Getting method stream multiple times");
        } else { // first time
            HTTPMethodStream stream = null;
            try {
                if(response == null) return null;
                stream = new HTTPMethodStream(response.getEntity().getContent(), this);
            } catch (Exception e) {
                stream = null;
            }
            this.methodstream = stream;
        }
        return this.methodstream;
    }

    public byte[] getResponseAsBytes(int maxbytes)
    {
        byte[] contents = getResponseAsBytes();
        if(contents.length > maxbytes) {
            byte[] result = new byte[maxbytes];
            System.arraycopy(contents,0,result,0,maxbytes);
            contents = result;
        }
        return contents;
    }

    public byte[] getResponseAsBytes()
    {
        if(closed)
            throw new IllegalStateException("HTTPMethod: method is closed") ;
        byte[] content = null;
        if(response != null)
        try {
            content = EntityUtils.toByteArray(response.getEntity());
        } catch (Exception e) {/*ignore*/}
        return content;
    }

    public String getResponseAsString(String charset)
    {
        if(closed)
            throw new IllegalStateException("HTTPMethod: method is closed") ;
        String content = null;
        if(response != null)
        try {
            Charset cset = Charset.forName(charset);
            content = EntityUtils.toString(response.getEntity(),cset);
        } catch (Exception e) {/*ignore*/}
        close();//getting the response will disallow later stream
        return content;
    }

    public String getResponseAsString()
    {
        return getResponseAsString("UTF-8");
    }

    public void setMethodHeaders(List<Header> headers) throws HTTPException
    {
        try {
            for(Header h : headers) {
                this.headers.add(h);
            }
        } catch (Exception e) {
            throw new HTTPException(e);
        }
    }

    public void setRequestHeader(String name, String value) throws HTTPException
    {
        setRequestHeader(new BasicHeader(name, value));
    }

    public void setRequestHeader(Header h) throws HTTPException
    {
        try {
            headers.add(h);
        } catch (Exception e) {
            throw new HTTPException("cause", e);
        }
    }

    public Header getRequestHeader(String name)
    {
        if(this.method == null)
            return null;
        try {
            return (this.method.getFirstHeader(name));
        } catch (Exception e) {
            return null;
        }
    }

    public Header[] getRequestHeaders()
    {
        if(this.method == null)
            return null;
        try {
            Header[] hs = this.method.getAllHeaders();
            return hs;
        } catch (Exception e) {
            return null;
        }
    }

    public Header getResponseHeader(String name)
    {
        try {
            return this.response.getFirstHeader(name);
        } catch (Exception e) {
            return null;
        }
    }

    public Header[] getResponseHeaders()
    {
        try {
            Header[] hs = this.response.getAllHeaders();
            return hs;
        } catch (Exception e) {
            return null;
        }
    }

    public void setRequestParameter(String name, Object value)
    {
        params.put(name, value);
    }

    public Object getMethodParameter(String key)
    {
        if(this.method == null)
            return null;
        return method.getParams().getParameter(key);
    }

    public HttpParams getMethodParameters()
    {
        if(method == null)
            return null;
        return method.getParams();
    }

    public Object getResponseParameter(String name)
    {
        if(method == null)
            return null;
        return method.getParams().getParameter(name);
    }


    public void setRequestContentAsString(String content) throws HTTPException
    {
        try {
            this.content = new StringEntity(content, "application/text", "UTF-8");
        } catch (UnsupportedEncodingException ue) {
        }
    }

    //todo:
    // public void setMultipartRequest(Part[] parts) throws HTTPException
    //{
    //    multiparts = new Part[parts.length];
    //    for(int i = 0;i < parts.length;i++) {
    //        multiparts[i] = parts[i];
    //    }
    //}

    public String getCharSet()
    {
        return "UTF-8";
    }

    public String getName()
    {
        return method == null ? null : method.getMethod();
    }

    public String getURL()
    {
        return method == null ? null : method.getURI().toString();
    }

    public String getProtocolVersion()
    {
        String ver = null;
        if(method != null) {
            ver = method.getProtocolVersion().toString();
        }
        return ver;
    }

    public String getSoTimeout()
    {
        return method == null ? null : "" + method.getParams().getParameter(AllClientPNames.SO_TIMEOUT);
    }

    public String getStatusText()
    {
        return getStatusLine();
    }

    public static Set<String> getAllowedMethods()
    {
        HttpResponse rs = new BasicHttpResponse(new ProtocolVersion("http",1,1),0,"");
        Set<String> set = new HttpOptions().getAllowedMethods(rs);
        return set;
    }

    // Convenience methods to minimize changes elsewhere

    public void setFollowRedirects(boolean tf)
    {
        //ignore ; always done
    }

    public String getResponseCharSet()
    {
        return "UTF-8";
    }


    public HTTPSession
    getSession()
    {
        return this.session;
    }

    public boolean
    isSessionLocal()
    {
        return this.localsession;
    }

    public HttpRequest
    getMethod()
    {
        return method;
    }

    public boolean hasStreamOpen()
    {
        return methodstream != null;
    }

    public boolean isClosed()
    {
        return this.closed;
    }

    /**
     * Test that the given url is "compatible" with the
     * session specified dataset. Compatible means:
     * 1. remove any query
     * 2. HTTPAuthStore.compatibleURL must return true;
     * <p/>
     * * @param url  to test for compatibility
     *
     * @return
     */
    boolean sessionCompatible(String other)
    {
        // Remove any trailing constraint
        String sessionurl = HTTPSession.getCanonicalURL(this.session.getURL());
        if(sessionurl == null) return true; // always compatible
        other = HTTPSession.getCanonicalURL(other);
        return HTTPAuthStore.compatibleURL(sessionurl, other);
    }

    /**
     * Handle authentication.
     * We do not know, necessarily,
     * which scheme(s) will be
     * encountered, so most testing
     * occurs in HTTPAuthProvider
     */

    static synchronized private void
    setAuthentication(HTTPSession session, HTTPMethod method)
    {
        String url = session.getURL();
        if(url == null) url = HTTPAuthStore.ANY_URL;

        // Provide a credentials (provider) to enact the process
        CredentialsProvider cp = new HTTPAuthProvider(url, method);
        // Since we not know where this will get called, do everywhere
        if(session != null && session.sessionClient != null) {
            session.sessionClient.setCredentialsProvider(cp);
        }
        //HttpParams hcp = session.sessionClient.getConnectionManager().getParams();
        //hcp.setParameter(CredentialsProvider.PROVIDER, cp);

    }

}
