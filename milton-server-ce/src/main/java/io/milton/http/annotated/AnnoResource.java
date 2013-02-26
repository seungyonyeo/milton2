/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.milton.http.annotated;

import io.milton.annotations.Get;
import io.milton.common.JsonResult;
import io.milton.common.ModelAndView;
import io.milton.http.AclUtils;
import io.milton.http.Auth;
import io.milton.http.ConditionalCompatibleResource;
import io.milton.http.FileItem;
import io.milton.http.HttpManager;
import io.milton.http.LockInfo;
import io.milton.http.LockResult;
import io.milton.http.LockTimeout;
import io.milton.http.LockToken;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.Request.Method;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.LockedException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.http.exceptions.PreConditionFailedException;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.http.values.HrefList;
import io.milton.principal.DiscretePrincipal;
import io.milton.principal.Principal;
import io.milton.resource.AccessControlledResource;
import io.milton.resource.CollectionResource;
import io.milton.resource.CopyableResource;
import io.milton.resource.DeletableResource;
import io.milton.resource.DigestResource;
import io.milton.resource.GetableResource;
import io.milton.resource.LockableResource;
import io.milton.resource.MoveableResource;
import io.milton.resource.PostableResource;
import io.milton.resource.PropFindableResource;
import io.milton.resource.ReportableResource;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author brad
 */
public abstract class AnnoResource implements GetableResource, PropFindableResource, DeletableResource, CopyableResource, MoveableResource, LockableResource, ConditionalCompatibleResource, CommonResource, DigestResource, PostableResource, ReportableResource, AccessControlledResource {
	
	private static final Logger log = LoggerFactory.getLogger(AnnoResource.class);
	protected Object source;
	protected final AnnotationResourceFactory annoFactory;
	protected AnnoCollectionResource parent;
	protected JsonResult jsonResult;
	protected String nameOverride;
	
	public AnnoResource(final AnnotationResourceFactory outer, Object source, AnnoCollectionResource parent) {
		if (source == null) {
			throw new RuntimeException("Source object is required");
		}
		this.annoFactory = outer;
		this.source = source;
		this.parent = parent;
	}
	
	@Override
	public String processForm(Map<String, String> parameters, Map<String, FileItem> files) throws BadRequestException, NotAuthorizedException, ConflictException {
		Request request = HttpManager.request();
		Object result = annoFactory.postAnnotationHandler.execute(this, request, parameters);
		if (result instanceof String) {
			String redirect = (String) result;
			return redirect;
		} else if (result instanceof JsonResult) {
			jsonResult = (JsonResult) result;
		} else {
			jsonResult = JsonResult.returnData(getHref(), result);
		}
		return null;
	}
	
	@Override
	public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
		if (jsonResult == null) {
			annoFactory.getAnnotationHandler.execute(this, out, range, params, contentType);
		} else {
			JsonWriter jsonWriter = new JsonWriter();
			jsonWriter.write(jsonResult, out);
		}
	}
	
	@Override
	public String getUniqueId() {
		return annoFactory.uniqueIdAnnotationHandler.get(source);
	}
	
	@Override
	public String getName() {
		if (nameOverride != null) {
			return nameOverride;
		}
		return annoFactory.nameAnnotationHandler.get(source);
	}
	
	@Override
	public Object authenticate(String user, String password) {
		AnnoPrincipalResource userRes = annoFactory.usersAnnotationHandler.findUser(getRoot(), user);
		if (userRes != null) {
			Boolean b = annoFactory.authenticateAnnotationHandler.authenticate(userRes, password);
			if (b != null) {
				return userRes;
			}
		}
		return annoFactory.getSecurityManager().authenticate(user, password);
	}
	
	@Override
	public Object authenticate(DigestResponse digestRequest) {
		AnnoPrincipalResource userRes = annoFactory.usersAnnotationHandler.findUser(getRoot(), digestRequest.getUser());
		if (userRes != null) {
			Boolean b = annoFactory.authenticateAnnotationHandler.authenticate(userRes, digestRequest);
			if (b != null) {
				return userRes;
			}
		}
		return annoFactory.getSecurityManager().authenticate(digestRequest);
	}
	
	@Override
	public boolean authorise(Request request, Method method, Auth auth) {
		Object oUser = null;
		if (auth != null) {
			oUser = auth.getTag();
		}
		AnnoPrincipalResource p = null;
		if (oUser instanceof AnnoPrincipalResource) {
			p = (AnnoPrincipalResource) oUser;
		}
		// only check ACL if current user is null (ie guest) or the current user is an AnnoPrincipal
		if (oUser == null || p != null) {
			Set<AccessControlledResource.Priviledge> acl = annoFactory.accessControlListAnnotationHandler.availablePrivs(p, this, method, auth);
			if (acl != null) {
				AccessControlledResource.Priviledge requiredPriv = annoFactory.accessControlListAnnotationHandler.requiredPriv(this, method, request);
				boolean allows;
				if (requiredPriv == null) {
					allows = true;
				} else {
					allows = AclUtils.containsPriviledge(requiredPriv, acl);
					if (!allows) {
						if (p != null) {
							log.info("Authorisation declined for user: " + p.getName());
						} else {
							log.info("Authorisation declined for anonymous access");
						}
						log.info("Required priviledge: " + requiredPriv + " was not found in assigned priviledge list of size: " + acl.size());
					}
				}
				return allows;
			} else {
				// null ACL means do not apply ACL
			}
		}
		// if we get here it means ACL was not applied, so we check default SM
		return annoFactory.getSecurityManager().authorise(request, method, auth, this);
	}
	
	@Override
	public String getRealm() {
		return annoFactory.getSecurityManager().getRealm(HttpManager.request().getHostHeader());
	}
	
	@Override
	public Date getModifiedDate() {
		return annoFactory.modifiedDateAnnotationHandler.get(source);
	}
	
	@Override
	public String checkRedirect(Request request) throws NotAuthorizedException, BadRequestException {
		return null;
	}
	
	@Override
	public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
		annoFactory.deleteAnnotationHandler.execute(source);
	}
	
	@Override
	public boolean isCompatible(Method m) {
		if (Method.PROPFIND.equals(m)) {
			return true;
		}
		return annoFactory.isCompatible(source, m);
	}
	
	@Override
	public boolean is(String type) {
		
		if (matchesType(source.getClass(), type)) {
			return true;
		}
		for (Class c : source.getClass().getClasses()) {
			if (matchesType(c, type)) {
				return true;
			}
		}
		
		return false;
	}
	
	@Override
	public Date getCreateDate() {
		return annoFactory.createdDateAnnotationHandler.get(source);
	}
	
	@Override
	public void moveTo(CollectionResource rDest, String name) throws ConflictException, NotAuthorizedException, BadRequestException {
		nameOverride = null; // reset any explicitly set name (eg for creating new resources)
		annoFactory.moveAnnotationHandler.execute(source, rDest, name);
	}
	
	public Object getSource() {
		return source;
	}
	
	public AnnotationResourceFactory getAnnoFactory() {
		return annoFactory;
	}
	
	@Override
	public AnnoCollectionResource getParent() {
		return parent;
	}
	
	@Override
	public void copyTo(CollectionResource toCollection, String name) throws NotAuthorizedException, BadRequestException, ConflictException {
		annoFactory.copyAnnotationHandler.execute(source, toCollection, name);
	}
	
	@Override
	public Long getMaxAgeSeconds(Auth auth) {
		ControllerMethod cm = annoFactory.getAnnotationHandler.getBestMethod(source.getClass());
		if (cm != null) {
			Get g = (Get) cm.anno;
			long l = g.maxAgeSecs();
			if (l == 0) {
				return null;
			} else if (l > 0) {
				return l;
			} // otherwise fall through to system default

			// if return type is a ModelAndView then we know its templated, so should have null max ag
			if (ModelAndView.class.isAssignableFrom(cm.method.getReturnType())) {
				return null;
			}
		}
		Long l = annoFactory.maxAgeAnnotationHandler.get(source);
		return l;
	}
	
	@Override
	public String getContentType(String accepts) {
		if (accepts != null && accepts.contains("application/json")) {
			return "application/json";
		}
		return annoFactory.contentTypeAnnotationHandler.get(source);
	}
	
	@Override
	public Long getContentLength() {
		return annoFactory.contentLengthAnnotationHandler.get(source);
	}
	
	@Override
	public boolean isDigestAllowed() {
		return annoFactory.getSecurityManager().isDigestAllowed();
	}
	
	public ResourceList getAsList() {
		ResourceList l = new ResourceList();
		l.add(this);
		return l;
	}
	
	private boolean matchesType(Class c, String type) {
		String name = c.getCanonicalName();
		int pos = name.lastIndexOf(".");
		name = name.substring(pos);
		if (name.equalsIgnoreCase(type)) {
			return true;
		}
		return false;
	}
	
	public String getHref() {
		if (parent == null) {
			return "/";
		} else {
			String s = parent.getHref() + getName();
			if (this instanceof CollectionResource) {
				s += "/";
			}
			return s;
		}
	}
	
	public AnnoCollectionResource getRoot() {
		return parent.getRoot();
	}
	
	public String getLink() {
		return "<a href=\"" + getHref() + "\">" + getName() + "</a>";
	}
	
	public String getDisplayName() {
		return annoFactory.displayNameAnnotationHandler.execute(this);
	}
	
	@Override
	public LockResult lock(LockTimeout timeout, LockInfo lockInfo) throws NotAuthorizedException, PreConditionFailedException, LockedException {
		return annoFactory.getLockManager().lock(timeout, lockInfo, this);
	}
	
	@Override
	public LockResult refreshLock(String token) throws NotAuthorizedException, PreConditionFailedException {
		return annoFactory.getLockManager().refresh(token, this);
	}
	
	@Override
	public void unlock(String tokenId) throws NotAuthorizedException, PreConditionFailedException {
		annoFactory.getLockManager().unlock(tokenId, this);
	}
	
	@Override
	public LockToken getCurrentLock() {
		return annoFactory.getLockManager().getCurrentToken(this);
	}
	
	public String getNameOverride() {
		return nameOverride;
	}
	
	public void setNameOverride(String nameOverride) {
		this.nameOverride = nameOverride;
	}
	
	@Override
	public HrefList getPrincipalCollectionHrefs() {
		List<AnnoCollectionResource> list = annoFactory.usersAnnotationHandler.findUsersCollections(getRoot());
		HrefList l = new HrefList();
		for (AnnoCollectionResource col : list) {
			l.add(col.getHref());
		}
		return l;
	}
	
	@Override
	public List<Priviledge> getPriviledges(Auth auth) {
		AnnoPrincipalResource curUser = null;
		if (auth != null && auth.getTag() instanceof AnnoPrincipalResource) {
			curUser = (AnnoPrincipalResource) auth.getTag();
		}
		Set<Priviledge> set = annoFactory.accessControlListAnnotationHandler.availablePrivs(curUser, this, null, auth);
		if (set != null && !set.isEmpty() ) {
			return new ArrayList<Priviledge>(set);
		} else {
			log.warn("Empty privs for: " + curUser);
			return Collections.EMPTY_LIST;
		}
	}
	
	@Override
	public void setAccessControlList(Map<Principal, List<Priviledge>> privs) {
	}
	
	@Override
	public Map<Principal, List<Priviledge>> getAccessControlList() {
		log.warn("getAccessControlList - not implemented");
		return null;
	}
	
	@Override
	public String getPrincipalURL() {
		// make the assumption that the owner is the first parent resource which implements Principal
		AnnoCollectionResource p = getParent();
		while (p != null) {
			if (p instanceof DiscretePrincipal) {
				return p.getHref();
			}
			p = p.getParent();
		}
		return null;
	}
}
