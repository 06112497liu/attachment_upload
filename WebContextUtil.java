package com.tellyes.core.utils;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import lombok.extern.slf4j.Slf4j;

/**
 * web工具类，可以获取当前请求的 request
 * 
 * @author xiniao
 *
 */
@Slf4j
public final class WebContextUtil {

	private static ServletRequestAttributes getServletWebRequest() {
		return (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
	}

	/**
	 * SpringMvc下获取session
	 * 
	 * @return
	 */
	public static HttpSession getSession() {
		HttpSession session = getRequest().getSession();
		return session;
	}

	/**
	 * SpringMvc下获取request
	 * 
	 * @return
	 */
	public static HttpServletRequest getRequest() {
		return getServletWebRequest().getRequest();

	}

	/**
	 * SpringMvc下获取response
	 * 
	 * @return
	 */
	public static HttpServletResponse getResponse() {
		return getServletWebRequest().getResponse();

	}

	/**
	 * 设置异常结果的返回 调用改方法后，应该立即return
	 * 
	 * @param message
	 * @param e
	 * @author zhangxueshu
	 */
	public static void errorResponse(String message, Exception e) {
		errorResponse(message, e, null);
	}

	/**
	 * 设置异常结果的返回 调用改方法后，应该立即return
	 * 
	 * @param message
	 * @param e
	 * @author zhangxueshu
	 */
	public static void errorResponse(String message, Exception e, HttpServletResponse response) {
		try {
			StringBuilder sb = new StringBuilder();
			sb.append("<!DOCTYPE html>" + "<html>" + "<head>" + "<meta charset='UTF-8'>"
					+ "<meta name='viewport' content='initial-scale=1, user-scalable=0, minimal-ui' charset='UTF-8'>"
					+ "<title>错误提示</title>" + "<meta name='full-screen' content='yes'>"
					+ "<meta name='x5-fullscreen' content='true'>" + "<style type='text/css'>"
					+ "*{margin:0;padding:0;font-family: 'Microsoft YaHei';}"
					+ ".container{width: 70%;margin: 100px auto 50px;color:#333;}"
					+ "h4{font-weight: normal;line-height: 40px;}" + "a{color:#7EB3EB;}"
					+ "p{margin-top: 40px;display: none;}" + "</style>" + "</head>" + "<body>"
					+ "<div class='container'>" + "<h4>");
			sb.append(message);
			sb.append("</h4>");
			if (e != null) {
				StringWriter stringWriter = new StringWriter();
				PrintWriter printWriter = new PrintWriter(stringWriter);
				e.printStackTrace(printWriter);
				sb.append("<h4>具体原因请查看 <a href='javascript:void(0)'>详情>></a></h4><p>");
				sb.append(stringWriter.toString());
				sb.append("</p>");
			}
			sb.append("</div><script type='text/javascript'>" + "window.onload = function(){"
					+ "document.getElementsByTagName('a')[0].onclick = function(){"
					+ "document.getElementsByTagName('p')[0].style.display = 'block';" + "}" + "}" + "</script>"
					+ "</body>" + "</html>");

			// 将错误信息写到response中
			if (response == null) {
				response = getServletWebRequest().getResponse();
			}
			response.setHeader("Content-type", "text/html;charset=utf-8");
			ByteArrayInputStream bais = new ByteArrayInputStream(sb.toString().getBytes("UTF-8"));
			byte[] buf = new byte[1024];
			int len;
			OutputStream out = response.getOutputStream();
			while ((len = bais.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
			bais.close();
			out.close();
		} catch (Exception ex) {
			log.error("处理异常返回时报错", ex);
		}
	}
}
