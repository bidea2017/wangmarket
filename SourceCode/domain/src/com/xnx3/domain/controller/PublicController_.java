package com.xnx3.domain.controller;

import java.util.Vector;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.aliyun.openservices.log.common.LogItem;
import com.aliyun.openservices.log.exception.LogException;
import com.xnx3.DateUtil;
import com.xnx3.StringUtil;
import com.xnx3.j2ee.func.AttachmentFile;
import com.xnx3.j2ee.service.SqlService;
import com.xnx3.j2ee.shiro.ShiroFunc;
import com.xnx3.j2ee.util.IpUtil;
import com.xnx3.j2ee.util.TerminalDetection;
import com.xnx3.net.HttpResponse;
import com.xnx3.net.HttpUtil;
import com.xnx3.net.OSSUtil;
import com.xnx3.admin.entity.Site;
import com.xnx3.domain.G;
import com.xnx3.domain.bean.RequestLog;
import com.xnx3.domain.bean.SimpleSite;
import com.xnx3.domain.vo.SImpleSiteVO;

/**
 * @author 管雷鸣
 */
@Controller
@RequestMapping("/")
public class PublicController_ extends BaseController {
	private HttpUtil http = new HttpUtil(HttpUtil.UTF8);
	
	@Resource
	private SqlService sqlService;

	/**
	 * 域名捕获转发
	 * @param htmlFile 访问的html文件，如访问c202.html ，则传入c202，会自动拼接上.html
	 * @param request 可get传入 domain 模拟访问的域名。可传入自己绑定的域名，也可传入二级域名。如domain=leiwen.wang.market
	 */
	@RequestMapping("dns")
	public String dns(HttpServletRequest request, HttpServletResponse response, Model model,
			@RequestParam(value = "htmlFile", required = false , defaultValue="") String htmlFile){
		SImpleSiteVO simpleSiteVO = getCurrentSimpleSite(request);
		
		//判断访问的站点的哪个文件
		if(htmlFile.length() == 0){
			htmlFile = "index";
		}
		htmlFile = htmlFile + ".html";
		
		//访问日志记录
		requestLog(request, htmlFile);
		
		if(simpleSiteVO.getResult() - SImpleSiteVO.FAILURE == 0){
			return error404();
		}else{
			//判断网站的状态，冻结的网站将无法访问
			SimpleSite simpleSite = simpleSiteVO.getSimpleSite();
			if(simpleSite.getState() != null){
				if(simpleSite.getState() - Site.STATE_FREEZE == 0){
					//2为冻结，暂停，此时访问网站会直接到冻结的提示页面
					model.addAttribute("url", simpleSiteVO.getServerName()+"/"+htmlFile);
					return "domain/pause";
				}
			}
			
			String html = AttachmentFile.getTextByPath("site/"+simpleSite.getId()+"/"+htmlFile);
			if(html == null){
				//判断一下是否是使用的OSS，并且配置了，如果没有配置，那么控制台给出提示
				if(AttachmentFile.isMode(AttachmentFile.MODE_ALIYUN_OSS) && OSSUtil.getOSSClient() == null){
					System.out.println("您未开启OSS对象存储服务！网站访问是必须通过读OSS数据才能展现出来的。开启可参考：http://www.guanleiming.com/2327.html");
				}
				return "domain/404";
			}
			
//			InputStream in = ossObj.getObjectContent();
//			String html = null;
//			try {
//				html = IOUtils.readStreamAsString(in, "UTF-8");
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
			
//			html = html.replaceAll("(?m)^\\s*$"+System.lineSeparator(), "");
			
			//如果用的第六套模版，需要进行手机电脑自适应
			if(simpleSite.getTemplateId() - 6 == 0){
				//判断是否增加了 viewport，若没有，补上
				if(html.indexOf("<meta name=\"viewport\"") == -1){
					String equiv = "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">";
					int ins = -1;
					int equivInt = html.indexOf(equiv);
					if(equivInt > -1){
						ins = equivInt + equiv.length();
					}
					html = StringUtil.insert(html, "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=no\">", ins);
				}
			}
			
			html = replaceHtmlTag(simpleSite, html);
			//过滤掉空行
//			html = html.replaceAll("((\r\n)|\n)[\\s\t ]*(\\1)+", "$1").replaceAll("^((\r\n)|\n)", ""); 
			//加载在线客服
			int time = DateUtil.timeForUnix10();
			html = html + ""
					+ "<script src=\"http://res.weiunity.com/js/fun.js\"></script>"
					+ "<script src=\""+AttachmentFile.netUrl()+"/site/"+simpleSite.getId()+"/data/site.js?v="+time+"\"></script>"
					+ "<script src=\"http://res.weiunity.com/js/im/site_kefu.js\"></script>"
					+ "";
			model.addAttribute("html", html);
			
			//判断此网站的类型，是PC端还是手机端
			if(simpleSite.getClient() - Site.CLIENT_WAP == 0){
				//手机端
				//如果是电脑浏览的，会显示经过处理的电脑页面，有个手机模拟器
				//如果是手机访问的，也是使用二级域名进行访问
				boolean isMobile = TerminalDetection.checkMobileOrPc(request);
				if(!isMobile){
					model.addAttribute("url", AttachmentFile.netUrl()+"site/"+simpleSite.getId()+"/"+htmlFile);
					return "domain/pcPreview";
				}
			}
			return "domain/pc";
		}
	}
	
	private void requestLog(HttpServletRequest request, String htmlFile){
		//进行记录访问日志，记录入Session
		RequestLog requestLog = (RequestLog) request.getSession().getAttribute("requestLog");
		if(requestLog == null){
			requestLog = new RequestLog();
			requestLog.setIp(IpUtil.getIpAddress(request));
			requestLog.setServerName(request.getServerName());
			
			SImpleSiteVO vo = (SImpleSiteVO) request.getSession().getAttribute("SImpleSiteVO");
			if(vo != null){
				requestLog.setSiteid(vo.getSimpleSite().getId());
			}
			
			request.getSession().setAttribute("requestLog", requestLog);
		}
		
		LogItem logItem = new LogItem(DateUtil.timeForUnix10());
		logItem.PushBack("ip", requestLog.getIp());
		logItem.PushBack("referer", request.getHeader("referer"));
		logItem.PushBack("userAgent", request.getHeader("User-Agent"));
		logItem.PushBack("htmlFile", htmlFile);
		logItem.PushBack("siteid", requestLog.getSiteid()+"");
		requestLog.getLogGroup().add(logItem);
		
		if(requestLog.getLogGroup().size() > 1000){
			try {
				G.aliyunLogUtil.saveByGroup(requestLog.getServerName(), requestLog.getIp(), requestLog.getLogGroup());
				requestLog.setLogGroup(new Vector<LogItem>());
			} catch (LogException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * sitemap.xml展示
	 * @param request {@link HttpServletRequest}
	 * @param model {@link Model}
	 */
	@RequestMapping("sitemap.do")
	public String sitemap(HttpServletRequest request, Model model){
		SImpleSiteVO simpleSiteVO = getCurrentSimpleSite(request);
		if(simpleSiteVO.getResult() - SImpleSiteVO.FAILURE == 0){
			return error404();
		}else{
			//访问日志记录
			requestLog(request, "sitemap.xml");
			
			HttpResponse hr = http.get(AttachmentFile.netUrl()+"site/"+simpleSiteVO.getSimpleSite().getId()+"/sitemap.xml");
			if(hr.getCode() - 404 == 0){
				return error404();
			}else{
				model.addAttribute("html", hr.getContent());
				return "domain/sitemap";
			}
		}
	}
	
	/**
	 * 获取当前用户访问的域名对应的站点
	 * @param request 可get传入 domain 模拟访问的域名。可传入自己绑定的域名，也可传入二级域名。如传入leiwen.wang.market
	 * @return
	 */
	private SImpleSiteVO getCurrentSimpleSite(HttpServletRequest request){
		//当前访问域名的对应站点，先从Session中拿
		SImpleSiteVO vo = (SImpleSiteVO) request.getSession().getAttribute("SImpleSiteVO");
		
		String serverName = request.getParameter("domain");	//get传入的域名，如 pc.wang.market
		if(serverName != null && vo != null && !vo.getServerName().equalsIgnoreCase(serverName)){
			//当get传入的domain有值，且值跟之前缓存的simpleSiteVO的值不对应，那么应该是代理商在用，在编辑多个网站。之前的网站退出了，又上了一个网站，正在预览当前的网站。那么清空之前的网站再session的缓存，重新进行缓存
			vo = null;
		}
		
		if(vo == null){
			//Session中没有SImpleSiteVO ，第一次用，那就找内存中的吧
			vo = new SImpleSiteVO();
			
			if(serverName == null || serverName.length() == 0){
				serverName = request.getServerName();	//访问域名，如 pc.wang.market
			}
			vo.setServerName(serverName);
			SimpleSite simpleSite = null;
			
			//内部调试使用，本地
			if(serverName.equals("localhost") || serverName.equals("127.0.0.1")){
				//模拟一个站点提供访问
				simpleSite = new SimpleSite();
				simpleSite.setBindDomain(serverName);
				simpleSite.setClient(Site.CLIENT_CMS);
				simpleSite.setDomain(serverName);
				simpleSite.setId(219);
				simpleSite.setState(Site.STATE_NORMAL);
				simpleSite.setTemplateId(1);
			}else{
				//正常使用，从域名缓存中找到对应的网站
				
				//判断当前访问域名是否是使用的二级域名
				String twoDomain = null;	
				for (int i = 0; i < G.getAutoAssignDomain().length; i++) {
					if(serverName.indexOf("."+G.getAutoAssignDomain()[i]) > -1){
						twoDomain = serverName.replace("."+G.getAutoAssignDomain()[i], "");
					}
				}
				
				if(twoDomain != null){
					//用的二级域名
					simpleSite = G.getDomain(twoDomain);
				}else{
					//自己绑定的域名
					simpleSite = G.getBindDomain(serverName);
				}
			}
			
			if(simpleSite == null){
				vo.setBaseVO(SImpleSiteVO.FAILURE, "网站没发现，过会在来看看吧");
				return vo;
			}
			vo.setSimpleSite(simpleSite);
			
			//将获取到的加入Session
			request.getSession().setAttribute("SImpleSiteVO", vo);
		}
		
		return vo;
	}
	
	/**
	 * 替换HTML标签
	 * @param simpleSite
	 * @param html
	 */
	public String replaceHtmlTag(SimpleSite simpleSite, String html){
		//替换掉 data目录下的缓存js文件
		html = html.replaceAll("src=\"data/", "src=\""+AttachmentFile.netUrl()+"site/"+simpleSite.getId()+"/data/");	
		//替换图片文件
		html = html.replaceAll("src=\"news/", "src=\""+AttachmentFile.netUrl()+"site/"+simpleSite.getId()+"/news/");
		//替换掉HTML的注释 <!-- -->
		//html = html.replaceAll("<!--(.*?)-->", "");
		//替换掉JS的注释 /**/
		//html = html.replaceAll("/\\*(.*?)\\*/", "");
		return html;
	}
	
}
