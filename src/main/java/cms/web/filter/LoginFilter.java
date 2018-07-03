﻿package cms.web.filter;

import java.io.IOException;
import java.util.Date;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

import cms.bean.user.AccessUser;
import cms.bean.user.RefreshUser;
import cms.bean.user.UserState;
import cms.service.user.UserService;
import cms.utils.Base64;
import cms.utils.SpringConfigTool;
import cms.utils.UUIDUtil;
import cms.utils.WebUtil;
import cms.utils.threadLocal.AccessUserThreadLocal;
import cms.web.action.common.OAuthManage;
import cms.web.action.user.UserManage;
import cms.web.taglib.Configuration;

/**
 * 检查登录用户跳转
 *
 */
public class LoginFilter implements Filter {

	public void destroy() {

	}

	public void doFilter(ServletRequest req, ServletResponse res,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest)req;
		HttpServletResponse response = (HttpServletResponse)res;

		boolean isJump = false;

		OAuthManage oAuthManage = (OAuthManage)SpringConfigTool.getContext().getBean("oAuthManage");
		UserManage userManage = (UserManage)SpringConfigTool.getContext().getBean("userManage");
		AccessUser accessUser = oAuthManage.getUserName(request);
		
		
		if(accessUser != null){
			AccessUserThreadLocal.set(accessUser);
			
			UserState userState = userManage.query_userState(accessUser.getUserName().trim());//用户状态
			if(userState != null){
				if(!userState.getSecurityDigest().equals(accessUser.getSecurityDigest())){//如果安全摘要有改变
					isJump = true;
				}
				
				if(userState.getState() !=1){// 如果不是正常用户   1:正常用户
					isJump = true;
				}
			}else{
				isJump = true;
			}
		}else{
			String accessToken = WebUtil.getCookieByName(request, "cms_accessToken");
			String refreshToken = WebUtil.getCookieByName(request, "cms_refreshToken");
			
			if(accessToken != null && !"".equals(accessToken.trim()) && refreshToken != null && !"".equals(refreshToken.trim())){
			
				RefreshUser refreshUser = oAuthManage.getRefreshUserByRefreshToken(refreshToken.trim());
				if(refreshUser != null){
					if("0".equals(refreshUser.getAccessToken())){//如果刷新令牌重复执行，则修改用户的安全摘要，让当前用户重新登录
						UserService userService = (UserService)SpringConfigTool.getContext().getBean("userServiceBean");
						
						userService.updateUserSecurityDigest(refreshUser.getUserName(),new Date().getTime());
						userManage.delete_userState(refreshUser.getUserName());
						isJump = true;
					}else if(accessToken.equals(refreshUser.getAccessToken())){
						//访问令牌续期
						String new_accessToken = UUIDUtil.getUUID32();
						String new_refreshToken = UUIDUtil.getUUID32();
						
						oAuthManage.addAccessToken(new_accessToken, new AccessUser(refreshUser.getUserId(),refreshUser.getUserName(),refreshUser.getSecurityDigest(),refreshUser.isRememberMe()));
						refreshUser.setAccessToken(new_accessToken);
						oAuthManage.addRefreshToken(new_refreshToken, refreshUser);
						
						//将旧的刷新令牌的accessToken设为0
						oAuthManage.addRefreshToken(refreshToken, new RefreshUser("0",refreshUser.getUserId(),refreshUser.getUserName(),refreshUser.getSecurityDigest(),refreshUser.isRememberMe()));
						AccessUserThreadLocal.set(new AccessUser(refreshUser.getUserId(),refreshUser.getUserName(),refreshUser.getSecurityDigest(),refreshUser.isRememberMe()));
						//将访问令牌添加到Cookie
						WebUtil.addCookie(response, "cms_accessToken", new_accessToken, 0);
						//将刷新令牌添加到Cookie
						WebUtil.addCookie(response, "cms_refreshToken", new_refreshToken, 0);
						
					}else{
						isJump = true;
					}
					
				}else{
					isJump = true;
				}
			
			}else{
				isJump = true;
			}
		}
		
		
		
		boolean isAjax = WebUtil.submitDataMode(request);
		
		if(isJump == true){
			
			String uri = request.getRequestURI();
			//获取URI后面的参数
			if(request.getQueryString() != null && !"".equals(request.getQueryString())){
				uri += "?"+request.getQueryString();
			}
			
			String jumpUrl = "";
			
			
			if(uri != null){
				
				if("POST".equals(request.getMethod())){
					String referer= request.getHeader("referer");
					if(referer != null && !"".equals(referer)){
						uri = StringUtils.removeStartIgnoreCase(referer,Configuration.getUrl(request));//移除开始部分的相同的字符,不区分大小写
					}
				}else{
					if(isAjax == true){//ajax方式提交
						String referer= request.getHeader("referer");  
						if(referer != null && !"".equals(referer)){
							uri = StringUtils.removeStartIgnoreCase(referer,Configuration.getUrl(request));//移除开始部分的相同的字符,不区分大小写
						}
						
					}else{
						String contextPath = request.getContextPath();
						
						// 删除虚拟目录
						if(contextPath != null && !"".equals(contextPath)){
							int old_uri_length = uri.length();
							
							uri = StringUtils.removeStartIgnoreCase(uri,contextPath+"/");//移除开始部分的相同的字符,不区分大小写 如shop/
							
							if(uri != null && old_uri_length != uri.length()){
								uri = StringUtils.removeStartIgnoreCase(uri,"/"+contextPath+"/");//移除开始部分的相同的字符,不区分大小写 如/shop/
							}
							
						}else{
							//删除开头的左斜杆
							uri = StringUtils.removeStartIgnoreCase(uri,"/");//移除开始部分的相同的字符,不区分大小写
						}
					}
					
				}
				
			}
			if(uri != null && !"".equals(uri.trim())){
				jumpUrl = Base64.encodeBase64URL(uri);//Base64安全编码
			}
			if(isAjax == true){//ajax方式提交
				response.setHeader("jumpPath", "login?jumpUrl="+jumpUrl);//设置登录页面响应http头。用来激活Ajax请求处理方式 Session超时后的跳转
				
			}else{
				String contextPath = request.getContextPath();
				response.sendRedirect((contextPath != null && !"".equals(contextPath.trim()) ? contextPath+"/" : "/")+"login?jumpUrl="+jumpUrl);
			}
			
			return;
		}
		
		chain.doFilter(req, res);
	}

	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub

	}

}