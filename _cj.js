

//*** blockUI ***********************************************************************************//
(function($){
	$(window).on("load resize scroll", function() {
		jf_blockUISizeSet();
	});
})(jQuery);

//escape 클릭시 blockUI Close
$(document).on('keyup',function(evt) {
	if($('.blockMsg').size() > 0){
		if (evt.keyCode == 27) {
			$.unblockUI();
			$("body").css("overflow-y","");
		}
	}
});
function jf_blockUISizeSet(){
	if($('.blockMsg').size() > 0){
		var w_screen = $(window).width(); //화면 가로 해상도를 구한다.
		//창크기에 따라 변환
		$('.blockMsg').css("width","780px");
		if(w_screen <= 768 && w_screen > 380){
			$('.blockMsg').css("width","90%");
		}else if(w_screen <= 380){
			$('.blockMsg').css("width","96%");
		}
		
		
		var widthVal = $('.blockMsg').width();
		if(widthVal != null){
			var leftVal = (w_screen/2)-(widthVal/2);
			$('.blockMsg').css("left",leftVal+"px");
		}
		
		var h_screen = $(window).height(); //화면 세로 해상도를 구한다.
		var heightVal = $('.blockMsg').height();
		if(heightVal != null){
			var topVal = (h_screen/2)-(heightVal/2);
			$('.blockMsg').css("top",topVal+"px");
		}
	}
}

$(function(){
	$(document).on("click","._blockUI",function(){
		if($(this).is("a")){
			var href = $(this).attr("href");
			//$(this).attr("target", "blockUI");
			
			var clsName = $(this).attr("class");
			var clsNames = clsName.split(" ");
			
			for(var i=0; i<clsNames.length; i++) {
				if(clsNames[i] == "_blockUI"
					|| clsNames[i].startsWith("icon-") 
					|| clsNames[i].indexOf("btn")>=0 
					|| clsNames[i].indexOf("Btn")>=0) {
					clsName = clsName.replace(clsNames[i], "");
				}
			}
			var className = clsName;
			
			var html = "";
			if( $(this).hasClass("_fnctAdmin")){
				html = 	"<div class=\"_blockInner\">"+
						"	<a href=\"#close\" onclick=\"unblockUIWithRefresh();\" class=\"_blockClose\">close</a>"+
						"	<iframe name=\"blockUI\" src=\""+href+"\" class='"+className+"'></iframe>"+
						"</div>";
			}else{
				html = 	"<div class=\"_blockInner\">"+
						"	<a href=\"#close\" onclick=\"$.unblockUI();\" class=\"_blockClose\">close</a>"+
						"	<iframe name=\"blockUI\" src=\""+href+"\" class='"+className+"'></iframe>"+
						"</div>";
			}
			
			var existOuterBlockUI = $(".blockMsg", parent.document).size() > 0;
			$.blockUI({
				message:html,
				bindEvents: false
			});
			if(existOuterBlockUI) {
				if(!$("body").hasClass("innerBlockBody")) {
					$("body").addClass("innerBlockBody");
				}
			}
			
			var blockClass = $('.blockMsg').attr("class") + " "+className;
			$('.blockMsg').attr("class", blockClass);
			
			jf_blockUISizeSet();
			return false;
		}
	});
	
	$('.textWrap').on('click', 'div', checkFormVal);
	
});

function blockUIWithUrl(url) {
	var html = "<a href=\"" + url + "\" class=\"_blockUI\" id=\"blockUIaTagTemp\" style=\"display: none;\">temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

function blockUI(customTag){
	if(customTag != null){
		
		if(customTag.indexOf("iframe") == -1){
			customTag = "<div class=\"_blockWrap\">"+customTag+"</div>";
		}
		
		var html = 	"<div class=\"_blockInner\">"+
					"	<a href=\"#close\" onclick=\"$.unblockUI();\" class=\"_blockClose\">close</a>"+
					"	"+customTag+""+
					"</div>";
		var existOuterBlockUI = $(".blockMsg", parent.document).size() > 0;
		$.blockUI({
			message:html
		});
		if(existOuterBlockUI) {
			if(!$("body").hasClass("innerBlockBody")) {
				$("body").addClass("innerBlockBody");
			}
		}
		
		jf_blockUISizeSet();
	}else{
		$.blockUI({ css: {             
		 	border: 'none',             
		 	padding: '15px',             
		 	backgroundColor: '#000',             
		 	'-webkit-border-radius': '10px',             
		 	'-moz-border-radius': '10px',             
		 	opacity: .5,             
		 	color: '#fff'         
		} }); 
	}
	
}

function unblockUIWithRefresh(){
	if(parent.parent.$(".blockUI").length > 0){
		parent.parent.location.reload();
	}
	$.unblockUI();
}

function unblockUI(){
	
	$.unblockUI();
}
//*** blockUI ***********************************************************************************//



/**
 * <input type="text" style="ime-mode:disabled;" onKeyPress="return numbersonly(event, false)">
 * @param e
 * @param decimal
 * @returns {Boolean}
 */
function numbersonly(e, decimal) { 
    var key; 
    var keychar; 

    if (window.event) { 
       // IE에서 이벤트를 확인하기 위한 설정 
	key = window.event.keyCode; 
    } else if (e) { 
      // FireFox에서 이벤트를 확인하기 위한 설정 
	key = e.which; 
    } else { 
	return true; 
    } 

    keychar = String.fromCharCode(key); 
    if ((key == null) || (key == 0) || (key == 8) || (key == 9) || (key == 13) 
	    || (key == 27) || (key == 45)) { 
	return true; 
    } else if ((("0123456789").indexOf(keychar) > -1)) { 
	return true; 
    } else if (decimal && (keychar == ".")) { 
	return true; 
    } else 
	return false; 
}

function trim(str) {  
	if(str != null){
		return str.replace( /(^\s*)|(\s*$)/g, "" );  
	}
	return str;
} 

function jf_ajaxSubmit(form) {
	var isChecked = true;
	var submitUrl = $(form).attr("action");
	var formData2 = {};
	var formData = $(form).serializeArray();
	$(formData).each(function () {
		if(this.name == "layout"){
			formData2[this.name] = "";
		}else{
			formData2[this.name] = this.value || "";
		}
	});
	
	$.ajax({
		type:"post",
		url:submitUrl,
		async:false,
		cache:false,
		data:formData2,
		success:function(r){
			isChecked = r == null || r == undefined || r == "";
			$(form).find("div._formCheck").remove();
			if( !isChecked ) {
				$(r).each(function() {
					$.each(this, function(key, value) {
						function onFocus(){
							if($("#"+key+"Str").length){
								$("#"+key+"Str").focus();
							}else{
								$("#"+key).focus();
							}
							
						}						
						if( key.indexOf( 'duplicate_' ) == -1 ) {
							//유효성 오류 검출시
							//alert으로 교체, 여러개일 때 첫번째 input에 focus
							alert( value, onFocus );
							
						} else {
							//중복 검출시
							alert( value, onFocus );
						}
					});
					return false;
				});
			}
		},
		error:function(){
			alert("error!");
		}
	});
	
	return isChecked;
}

function checkFormVal(){
	$(this).siblings("input").focus();
	$(this).remove();
}


var trueCheck = false;
function confirm(message, yesCallback, noCallback) {
	if(trueCheck){
		return trueCheck;
	}
	
	if($('#confirmDivTemp').size() == 0){
		var html ="" +
				"<div id='confirmDivTemp' class='_alertWrap'>" +
				"	<div class='_alertHeader'>" +
				"		<h2>"+ktext("k2web.confirm")+"</h2>" +
				"	</div>" +
				"	<div class='_alertBody'><p class='message'></p></div>" +
				"	<div class='_alertFooter'>" +
				"		<input type='button' class='confirmBtnOk' value='"+ktext("k2web.yes")+"'>" +
				"		<input type='button' class='confirmBtnCancel' value='"+ktext("k2web.no")+"'>" +
				"	</div>" +
				"</div>";
		$("body").append(html);
	}
	$('#confirmDivTemp').modal({
		//closeHTML: "<button title='Close' class='modal-close'>close</button>",
		overlayId: 'confirm-overlay',
		containerId: 'confirm-container', 
		dataId: 'confirm-data',
		/*onOpen: function (dialog) { 
            dialog.overlay.fadeIn(150, function () { 
                dialog.container.slideDown(150, function () { 
                	dialog.data.fadeIn(150); 
                }); 
            }); 
        }, */
		onShow: function (dialog) {
			var modal = this;

			$('.message', dialog.data[0]).append(message);
		
			$(".confirmBtnOk").on("click", function () {
				trueCheck = true;
				modal.close();
				if ($.isFunction(yesCallback)) {
					yesCallback();
				}
				trueCheck = false;
			});
			
			$(".confirmBtnCancel").on("click", function () {
				trueCheck = false;
				modal.close();
				if ($.isFunction(noCallback)) {
					noCallback();
				}
			});
			
			//$('.confirmBtnOk', dialog.data[0]).focus();
		}
	});
	var containerHeight = $("#confirmDivTemp").height() + 10;
	$("#confirm-container").css("height", containerHeight + "px");
	
	return trueCheck;
}

function alert(txt, okCallback){
	/*
	var html ="" +
	"<div id='alertDivTemp' class='_alertWrap'>" +
	"	<div class='_alertHeader'>" +
	"		<h2>Message</h2>" +
	"	</div>" +
	"	<div class='_alertBody'><p class='alertMessage'>"+txt+"</p></div>" +
	"	<div class='_alertFooter'>" +
	"		<input type='button' class='alertBtnOk' value='확인' onclick=\"unblockUI();\">" +
	"	</div>" +
	"</div>";
	blockUI(html);
	*/
	
	if($('#alertDivTemp').size() == 0){
		var html ="" +
				"<div id='alertDivTemp' class='_alertWrap'>" +
				"	<div class='_alertHeader'>" +
				"		<h2>"+ktext("k2web.alert")+"</h2>" +
				"	</div>" +
				"	<div class='_alertBody'><p class='alertMessage'></p></div>" +
				"	<div class='_alertFooter'>" +
				"		<input type='button' class='alertBtnOk' value='"+ktext("k2web.confirm")+"'>" +
				"	</div>" +
				"</div>";
		$("body").append(html);
	}
	
	if(txt != null && txt != "" && typeof txt == "string"){
		txt = txt.replace(/\n/gi, "<br/>");
	}
	
	$("#alertDivTemp .alertMessage").html(txt);
	$('#alertDivTemp').modal({
		//closeHTML: "<button title='Close' class='modal-close'>close</button>",
		overlayId: 'simplemodal-overlay',
		containerId: 'simplemodal-container'
	}); // HTML
	
	var containerHeight = $("#alertDivTemp").height() + 10;
	$("#simplemodal-container").css("height", containerHeight + "px");
	
	$(".alertBtnOk").on("click", function () {
		if($.isFunction(okCallback)){
			okCallback();
			$('.simplemodal-close').click();
		}else{
			$('.simplemodal-close').click();
		}
	});
}

/**
 * 공통 첨부파일 등록 폼
 * @param num	번호
 * @param fileType	파일타입
 * @param siteId	사이트 아이디
 */
function jf_fileUploadFormCommon(num, fileType, siteId, everybody){
	if(num == null){
		num = 0;
	}
	
	if(siteId == null){
		siteId = "";
	}
	
	/*
	$.ajax({
		url : kurl("/fileupload/registView"),
		type:"post",
		data:{
			"num":num,
			"fileType":fileType,
			"siteId":siteId
		},
		async:false,
		cache:false,
		success:function(r){
			$.blockUI({
				message:r,
				css:{
					width:"400px",
					padding:"15px",
					border:"0px"
				}
			});
		},error:function(){
			alert("server Error");
		}
	});
	*/
	
	if(!(typeof(window["jf_fileUploadCallback"]) == "function")){
		alert("jf_fileUploadCallback() does not exist!");
		return;
	}
	
	var blockUIClass = "_blockH350";
	if(fileType=="multi"){
		blockUIClass = "_blockH450";
	}

	var url = kurl("/fileupload/registView") + "?num="+num+"&fileType="+fileType+"&siteId="+siteId+"&everybody="+everybody;
	var html = "<a href='"+url+"' class='_blockUI _atchmnfl "+blockUIClass+"' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

var curAlt = "";
function jf_fileUploadProcCommon(num, fileType, siteId, everybody){
	curAlt = "";
	var fileTag = $("#fileCommonTag");
	var siteId = $("input[name='siteId']").val();
	if($(fileTag).val() == ""){
		alert(ktext("error.item.exist2", null, "k2web.select,k2web.file", siteId));
		return;
	}
	
	if(fileType == "image"){
		//이미지 설명 필수 입력
		curAlt = trim($("#alt").val());
		if(curAlt == ""){
			alert(ktext("msg.at.do3","","k2web.image.explanation,k2web.enter", siteId), function(){
				$("#alt").focus();
			});
			return;
		}
	}

	//var fileObj = $(fileTag)[0].files[0];
	var data = jf_fileUploadAjaxCommon(fileTag, fileType, siteId, num, "jf_fileUploadProcCommonResult", everybody);
	jf_fileUploadProcCommonResult(data);
}

function jf_fileUploadProcCommonResult(data){
	if(data != null && data.result){
		// >> 첨부파일 절대경로 노출
		//var fileDir = data.fileDir;
		var siteId = data.siteId;
		var today = data.today;
		// << 첨부파일 절대경로 노출
		var fileRename = data.fileRename;
		var fileOrg = data.fileOrg;
		var fileSize = data.fileSize;
		var num = data.num;
		// >> 첨부파일 절대경로 노출
		//parent.jf_fileUploadCallback(fileDir, fileOrg, fileRename, fileSize, num, curAlt);
		parent.jf_fileUploadCallback(siteId, today, fileOrg, fileRename, fileSize, num, curAlt);
		// << 첨부파일 절대경로 노출
		parent.unblockUI();
	}
}


/**
 * 파일 업로드
 * @param fileObj fileObj 파일객체
 * @param fileType fileType 업로드 타입 : image, media, 일반-""
 * @param siteId 사이트 아이디
 * @param num 파일 번호
 * @param callback ie9 환경에서 iframe 파일 업로드 후 실행되어야할 함수
 * @returns
 */
function jf_fileUploadAjaxCommon(fileObj, fileType, siteId, num, callback, everybody){
	
	if(fileObj == null){return null;}
	if(siteId == null){
		siteId = "";
	}
	if(num == null){
		num = "";
	}
	var result = null;
	if(window.FormData !== undefined){
		// for HTML5 browsers
		var fd = new FormData();
		fd.append("file",fileObj[0].files[0]);
		fd.append("fileType",fileType);
		fd.append("siteId",siteId);
		fd.append("num",num);
		fd.append("everybody",everybody);
		
		$.ajax({
			url: kurl("/fileupload/regist"),
			type: "POST",
			data: fd, 
			async: false,
			cache: false,
			contentType: false,
			processData: false,
			success:  function(data){
				if(data != null){
					if(data.result){
						result = data;
					}else{
						alert(data.message);
					}
				}
			}
		});
		return result;
		
	}else{
		
		if($("#fileIframe").size() == 0){
			$("body").append("<iframe name='fileIframe' src='#' id='fileIframe' class='hidden'/>");
		}

		var form = $(fileObj).closest("form");
		
		if(form == null){
			alert("No search form tag!");
			return;
		}
		
		$(form).attr("method", "post");
		$(form).attr("target", "fileIframe");
		$(form).attr("enctype", "multipart/form-data");
		$(form).attr("action",  kurl("/fileupload/regist"));
		
		if($(form).find("input[name='siteId']").size() == 0){
			$(form).append("<input type='hidden' name='siteId'/>");
		}
		if($(form).find("input[name='fileType']").size() == 0){
			$(form).append("<input type='hidden' name='fileType'/>");
		}
		if($(form).find("input[name='viewType']").size() == 0){
			$(form).append("<input type='hidden' name='viewType' value='html'/>");
		}
		if($(form).find("input[name='callback']").size() == 0){
			$(form).append("<input type='hidden' name='callback' value='"+callback+"'/>");
		}
		
		$(form).find("input[name='siteId']").val(siteId);
		$(form).find("input[name='fileType']").val(fileType);
		
		//*** ie9 3번 submit을 해야 전송이됨?? ****//
		$(form).submit();
		$(form).submit();
		$(form).submit();
		//*** ie9 3번 submit을 해야 전송이됨?? ****//
		
		$("#fileIframe").load(function(e)
        {
			var data = eval("("+$("#fileIframe").contents().find("body").text()+")");
			if(data != null){ 
				if(data.result){
					var callback = data.callback;
					eval(callback+"(data)");
				}else{
					alert(data.message);
				}
			}
        });
		
		return null;
	}
	
}

function getDoc(frame) {
    var doc = frame.contentWindow ? frame.contentWindow.document : frame.contentDocument ? frame.contentDocument : frame.document;

    return doc;
}

function curUrlPattern(){
	//var urlPattern = "";
	//var url = location.pathname;
	//if(url != null){
	//	urlPattern = url.substring( url.lastIndexOf("."));
	//}
	
	return urlPattern;
}

/**
 * k:url script 용
 * @param url
 * @returns
 */
function kurl(url){
	/*$.ajax({
		url:"/kurl" + curUrlPattern(),
		type:"post",
		data:{
			"url":url
		},
		async:false,
		cache:false,
		success:function(r){
			if(r != null && r.url != null){
				url = r.url;
			}
		}
	});*/
	return ctxPath + url + curUrlPattern();
}

/**
 * k:text script 용
 * @param key(필수)
 * @param lang(선택)
 * @param argsStr(선택)
 * @returns
 */
function ktext(key, lang, argsStr) {
	return ktext(key, lang, argsStr, "");
}
/**
 * k:text script 용
 * @param key(필수)
 * @param lang(선택)
 * @param argsStr(선택)
 * @param siteId(선택)
 * @returns
 */
function ktext(key, lang, argsStr, siteId) {
	/*
	 * ktext()의 경우 request가 달라 siteId를 전달하기 어려워
	 * - 관리자 : common_js.jsp
	 * - 사용자 : common_head.jsp
	 * 에서 default값을 지정함. 
	 */
	if( siteId==null || siteId=="" || siteId==undefined ) {
		siteId = defaultTextSiteId;
		
	}
	
	var text = ""; 
	$.ajax({
		url : "/ktext" + curUrlPattern(),
		type : "post",
		data : {
			"key" : key,
			"lang" : lang,
			"argsStr" : argsStr,
			"siteId":siteId
		},
		async : false,
		cache : false,
		success:function(r){
			if( r!=null && r.text!=null){
				text = r.text;
			}
		}
	});
	return text;
}

/**
 * 현재 언어설정값을 가져온다
 * @returns
 */
function klang() {
	/*
	 * ktext()의 경우 request가 달라 siteId를 전달하기 어려워
	 * - 관리자 : common_js.jsp
	 * - 사용자 : common_head.jsp
	 * 에서 default값을 지정함. 
	 */
	var siteId = "";
	if( siteId==null || siteId=="" || siteId==undefined ) {
		siteId = defaultTextSiteId;
	}
	
	var lang = "en"; 
	$.ajax({
		url : "/klang" + curUrlPattern(),
		type : "post",
		data : {
			"siteId":siteId
		},
		async : false,
		cache : false,
		success:function(r){
			if( r!=null && r.text!=null){
				lang = r.text;
			}
		}
	});
	return lang;
}

/**
 * 사용자조회
 * 사용자 선택 후 jf_userInfoListCallback(userId, userNm) 호출함
 * @param height
 */
function jf_userInfoList(siteId, height){
	if(!(typeof(window["jf_userInfoListCallback"]) == "function")){
		alert("jf_userInfoListCallback() does not exist!");
		return;
	}
	
	var url = kurl("/user/siteMngr/"+siteId+"/userInfoList");
	var html = "<a href='"+url+"' class='_blockUI _blockH"+height+"' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}


/**
 * 사용자조회(최고관리자 설정조회, 회원탈퇴 사용자조회, 사이트개설 관리자조회 )
 * 사용자 선택 후 jf_siteUserInfoListCallback(userId,) 호출함 
 */
function jf_searchUserInfoList(siteId,userId){
	if(!(typeof(window["jf_searchUserInfoListCallback"]) == "function")){
		alert("jf_searchUserInfoListCallback() does not exist!");
		return;
	}	 
	var url = kurl("/user/topMngr/searchUserList")+"?siteId="+siteId+"&userId="+userId;
	var html = "<a href='"+url+"' class='_blockUI' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

/**
 * 중간사용자 조회(최고관리자 설정조회, 회원탈퇴 사용자조회, 사이트개설 관리자조회 )
 * 사용자 선택 후 jf_searchMiddleUserInfoListCallback(userId,) 호출함 
 */
function jf_searchMiddleUserInfoList(siteId){
	if(!(typeof(window["jf_searchMiddleUserInfoListCallback"]) == "function")){
		alert("jf_searchMiddleUserInfoListCallback() does not exist!");
		return;
	}	 
	var url = kurl("/user/searchUserList")+"?siteId="+siteId;
	var html = "<a href='"+url+"' class='_blockUI' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

/**
 * 일반사용자 조회(조직도 조직회원 추가 조회)
 * 사용자 선택 후 jf_orgchtUserAllListCallback(userId,userNm) 호출함 
 */
function jf_orgchtUserAllList(){
	if(!(typeof(window["jf_orgchtUserAllListCallback"]) == "function")){
		alert("jf_orgchtUserAllListCallback() does not exist!");
		return;
	}	 
	var url = kurl("/orgcht/topMngr/orgchtUserAllList");
	var html = "<a href='"+url+"' class='_blockUI _blockH600' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

 

/**
 * 조직도 조회
 * 조직도 선택 후 jf_orgchtTreeListCallBack(orgchtCode, orgchtNm, isDown) 호출함
 * @param siteId
 * @param height
 */
function jf_orgchtTreeList(siteId, height){
	if(!(typeof(window["jf_orgchtTreeListCallBack"]) == "function")){
		alert("jf_orgchtTreeListCallBack() does not exist!");
		return;
	}
	
	var url = kurl("/fnctmngr/siteMngr/"+siteId+"/orgchtTree");
	var html = "<a href='"+url+"' class='_blockUI _blockH450' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

/**
 * (일반사용자를 위한)조직도 조회
 * 조직도 선택 후 jf_orgchtTreeListCallBack(orgchtCode, orgchtNm) 호출함
 * @param siteId 사이트 아이디
 */
function jf_orgchtTreeListForUser(siteId){

	if(!(typeof(window["jf_orgchtTreeListForUserCallBack"]) == "function")){
		alert("callback function does not exist!");
		return;
	}
	
	var url = siteId!=null && siteId!=undefined && siteId!=""? kurl("/orgcht/" + siteId + "/orgchtTree") : kurl( "/orgcht/orgchtTree" );
	var html = "<a href='"+url+"' class='_blockUI _blockH550' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}


/**
 * 기능 관리자 설정
 * @param fnctId 기능아이디
 * @param fnctNo 기능번호
 */
function jf_fnctMngr(siteId, fnctId, fnctNo){
	if(fnctNo == null){
		fnctNo = "";
	}
	var html = "<a href='"+kurl("/fnctmngr/siteMngr/"+siteId+"/userIdList")+"?fnctId="+fnctId+"&fnctNo="+fnctNo+"' class='_blockUI _blockH650' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

/**
 * 컨텐츠 관리자 설정
 * @param siteId 사이트아이디
 * @param menuSeq 메뉴번호
 */
function jf_cntntsCharger(siteId, menuSeq){
	var url = kurl("/cntntsCharger/fnctMngr/"+siteId+"/"+menuSeq+"/cntntsChargerView");
	var html = "<a href='"+url+"' class='_blockUI _blockH600' id='blockUIaTagTemp' style='display:none' >temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

/**
 * 마스터 스킨 목록 
 * => 선택 후 function jf_masterSkinSelectCallback(selMasterSkinId, selMasterSkinNm) 있어야함.
 * @param masterSkinId
 */
function jf_masterSkinList(masterSkinId){
	if(!(typeof(window["jf_masterSkinSelectCallback"]) == "function")){
		alert("jf_masterSkinSelectCallback() does not exist!");
		return;
	}
	
	var url = kurl("/masterskin/siteMngr/"+$("#siteId").val()+"/masterSkinList");
	if(masterSkinId != null){
		url += "?masterSkinId="+masterSkinId;
	}
	
	var html = "<a href='"+url+"' class='_blockUI _masterSkinList' id='blockUIaTagTemp' style='display:none'>"+ktext("k2web.select","","")+"</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
	
}


/**
 * 공통 접근 권한 설정
 * @param siteId 사이트 아이디
 * @param fnctId 기능 아이디
 * @param fnctNo 기능 번호
 * @param initViewValue 화면 초기 설정값 
 * 	String[] keys = {"list","view"};
	String[] texts = {"목록보기","내용보기"};
	String initViewValue = K2Util.accesAuthorInitValue(keys, texts);
 */
function jf_accesAuthor(siteId, fnctId, fnctNo, initViewValue){
	if(fnctNo == null){
		fnctNo = "";
	}
	var html = "<a href='"+kurl("/accesauthor/fnctMngr/"+siteId+"/list")+"?fnctId="+fnctId+"&fnctNo="+fnctNo+"&initViewValue="+initViewValue+"' class='_blockUI _blockH650' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

/**
 * .html() 시 자신의 태그는 가져오지 못함.
 * => 자신의 태그 까지 가져오기
 */
$.fn.outerHTML = function() {
    var el = $(this);
    if( !el[0] ) return "";
 
    if (el[0].outerHTML) {
        return el[0].outerHTML;
    } else {
        var content = el.wrap('<p/>').parent().html();
        el.unwrap();
        return content;
    }
}

function nyroModalClose(){
	$(".nyroModalClose").click();
}

function getAjax(url, data, async){
	var result = null;
	if(async == null){ async= false; }
	
	$.ajax({
		url : url,
		headers: { 'x-requested-with': 'k2web' },
		type : "post",
		data : data,
		cache:false,
		async:async,
		success:function(e){
			result = e;
		},
		error:function(){
			//alert("실패하였습니다.");
		}
	});
	return result;
}

/*
 * 2016-05-18 추가
 * 작성자 : 김경남
 * 요소 최대 번호 가져오기
*/ 
function _getEleMaxNum(siteId, menuSeq){
	var num = null;
	var r = getAjax(kurl( "/site/menuMngr/"+siteId+"/"+menuSeq+"/getEleMaxNum" ));
	if(r != null && r.maxNum != null){
		num = r.maxNum;
	}else{
		alert("MaxNum Fail!");
	}
	return num;
}

/**
 * 현재 서버시간 가져오기
 */
function getCurDate(){
	var url = kurl("/curDateJson");
	var r = getAjax(url);
	if(r != null && r.date != null){
		// yyyy/MM/dd HH:mm:ss
		var curDate = new Date( r.date );
		if(curDate != null){
			return curDate;
		}
	}
}

var StringBuffer = function() {
    this.buffer = new Array();
}

StringBuffer.prototype.append = function(obj) {
     this.buffer.push(obj);
}

StringBuffer.prototype.toString = function(){
     return this.buffer.join("");
}


/**
 * 대표이미지 등록 폼
 * @param num	번호
 * @param fileType	파일타입
 * @param siteId	사이트 아이디
 */
function jf_fileUploadFormCrop(num, fileType, siteId, w, h){
	if(siteId == null){
		siteId = "";
	}
	
	if(!(typeof(window["jf_fileUploadCallback"]) == "function")){
		alert("jf_fileUploadCallback() does not exist!");
		return;
	}	
	
	var url = kurl("/fileupload/cropRegistView") + "?num="+num+"&fileType="+fileType+"&siteId="+siteId+"&cropWidth="+w+"&cropHeight="+h;
	var html = "<a href='"+url+"' class='_blockUI _blockH800' id='blockUIaTagTemp' style='display:none'>temp</a>";
	$("body").append(html);
	$("#blockUIaTagTemp").click();
	$("#blockUIaTagTemp").remove();
}

function jf_tmpFileUploadProcCrop(num, fileType, siteId){
	var fileTag = $("#fileCommonTag");
	var siteId = $("input[name='siteId']").val();
	if($(fileTag).val() == ""){
		alert(ktext("error.item.exist2","","k2web.select,k2web.file", siteId));
		return;
	}
	
	var data = jf_fileUploadAjaxCommon(fileTag, fileType, siteId, num, "jf_fileUploadProcCommonResult");
	// >> 첨부파일 절대경로 노출
	//$('#img_wrap').html('<img id="cropImg" src="'+data.fileDir+data.fileRename+'" alt="" />');
	var fileDir = "";
	if(data.siteId != null && data.siteId != ""){
		fileDir = "/sites/"+data.siteId+"/tempFile/"+data.today+"/";
	}else{
		fileDir = "/tempFile/"+data.today+"/";
	}
	$('#img_wrap').html('<img id="cropImg" src="'+fileDir+data.fileRename+'" alt="" />');
	// << 첨부파일 절대경로 노출
	$img = $('#cropImg').imagesLoaded(function(){
		/*if($('#cropImg').width()<234 || $('#cropImg').height()<126){
			$('#cropImg').attr('src','/sysManager/images/sample_none_400x300.jpg');
			alert('가로 234px, 세로 126px 이상의 이미지를 등록하시기 바랍니다.');
			$sizeChk = false;
			
			$w = $('#cropImg').width()+60;
			$h = $('#cropImg').height()+210;
			window.resizeTo($w, $h);
			$('#img_wrap').width($w-45);
			$('#img_wrap').height($h-190);
			
			location.reload();
			
			return false;
		}*/
	});
	crop();
}

function crop(){
	var cropW = $("#cropW").val();
	var cropH = $("#cropH").val();
	$('#cropImg').Jcrop({
		aspectRatio: 1,
		onSelect: updateCoords,
		aspectRatio: cropW/cropH,
		minSize: [ cropW, cropH]
	});
	
	$('#cropImg').imagesLoaded(function(){
		$w = $('#cropImg').width()+60;
		$h = $('#cropImg').height()+210;
		if($w > 1024) $w = 1024;
		if($h > 768) $h = 768;
		window.resizeTo($w, $h);
		$('#img_wrap').width($w-45);
		$('#img_wrap').height($h-190);
	});
}

function updateCoords(c){
	$('#x').val(c.x);
	$('#y').val(c.y);
	$('#w').val(c.w);
	$('#h').val(c.h);
};

function jf_fileUploadProcCrop(num, fileType, siteId){
	curAlt = "";
	var fileTag = $("#fileCommonTag");
	var siteId = $("input[name='siteId']").val();
	if($(fileTag).val() == ""){
		alert(ktext("error.item.exist2","","k2web.select,k2web.file", siteId));
		return;
	}
	
	var fileName = $("#cropImg").attr("src");
	if(fileName == ""){
		alert(ktext("error.item.exist2","","k2web.select,k2web.file", siteId));
		return;
	}
	
	if(fileType == "image"){
		//이미지 설명 필수 입력
		curAlt = trim($("#alt").val());
		if(curAlt == ""){
			alert(ktext("msg.at.do3","","k2web.image.explanation,k2web.enter", siteId), function(){
				$("#alt").focus();
			});
			return;
		}
	}
	
	var checkNm = 'x,y,w,h';
	checkNmTemp = checkNm.split(',');
	for(i=0; i<checkNmTemp.length; i++){
		if($('#'+checkNmTemp[i]).val() == ''){
			alert(ktext("msg.select.image.area"));
			return false;
		}
	};
	
	var x = parseInt($("#x").val());
	var y = parseInt($("#y").val());
	var w = parseInt($("#w").val());
	var h = parseInt($("#h").val());
	
	var data = jf_fileUploadAjaxCrop(fileTag, fileName, fileType, siteId, num, x, y, w, h, "jf_fileUploadProcThumbResult");
	jf_fileUploadProcCropResult(data);
}

function jf_fileUploadProcCropResult(data){
	if(data != null && data.result){
		// >> 첨부파일 절대경로 노출
		//var fileDir = data.fileDir;
		var siteId = data.siteId;
		var today = data.today;
		// << 첨부파일 절대경로 노출
		var fileRename = data.fileRename;
		var fileOrg = data.fileOrg;
		var fileSize = data.fileSize;
		var num = data.num;
		
		// >> 첨부파일 절대경로 노출
		//parent.jf_fileUploadCallback(fileDir, fileOrg, fileRename, fileSize, num, curAlt);
		parent.jf_fileUploadCallback(siteId, today, fileOrg, fileRename, fileSize, num, curAlt);
		// << 첨부파일 절대경로 노출
		parent.unblockUI();
	}
}

/**
 * 썹네일 파일 업로드
 * @param fileObj fileObj 파일객체
 * @param fileType fileType 업로드 타입 : image, media, 일반-""
 * @param siteId 사이트 아이디
 * @param num 파일 번호
 * @param callback ie9 환경에서 iframe 파일 업로드 후 실행되어야할 함수
 * @returns
 */
function jf_fileUploadAjaxCrop(fileObj, fileName, fileType, siteId, num, x, y, w, h, callback){
	
	if(fileObj == null){return null;}
	if(fileName == null){return null;}

	if(siteId == null){
		siteId = "";
	}
	if(num == null){
		num = "";
	}
	var result = null;
	if(window.FormData !== undefined){
		// for HTML5 browsers
		var fd = new FormData();
		fd.append("file",fileObj[0].files[0]);
		fd.append("fileName",fileName);
		fd.append("fileType",fileType);
		fd.append("siteId",siteId);
		fd.append("num",num);
		fd.append("x",x);
		fd.append("y",y);
		fd.append("w",w);
		fd.append("h",h);
		
		$.ajax({
			url: kurl("/fileupload/cropRegist"),
			type: "POST",
			data: fd, 
			async: false,
			cache: false,
			contentType: false,
			processData: false,
			success:  function(data){
				if(data != null){
					if(data.result){
						result = data;
					}else{
						alert(data.message);
					}
				}
			}
		});
		return result;
		
	}else{
		
		if($("#fileIframe").size() == 0){
			$("body").append("<iframe name='fileIframe' src='#' id='fileIframe' class='hidden'/>");
		}

		var form = $(fileObj).closest("form");
		
		if(form == null){
			alert("No search form tag!");
			return;
		}
		
		$(form).attr("method", "post");
		$(form).attr("target", "fileIframe");
		$(form).attr("enctype", "multipart/form-data");
		$(form).attr("action",  kurl("/fileupload/cropRegist"));
		
		if($(form).find("input[name='siteId']").size() == 0){
			$(form).append("<input type='hidden' name='siteId'/>");
		}
		if($(form).find("input[name='fileType']").size() == 0){
			$(form).append("<input type='hidden' name='fileType'/>");
		}
		if($(form).find("input[name='viewType']").size() == 0){
			$(form).append("<input type='hidden' name='viewType' value='html'/>");
		}
		if($(form).find("input[name='callback']").size() == 0){
			$(form).append("<input type='hidden' name='callback' value='"+callback+"'/>");
		}
		
		$(form).find("input[name='siteId']").val(siteId);
		$(form).find("input[name='fileType']").val(fileType);
		
		//*** ie9 3번 submit을 해야 전송이됨?? ****//
		$(form).submit();
		$(form).submit();
		$(form).submit();
		//*** ie9 3번 submit을 해야 전송이됨?? ****//
		
		$("#fileIframe").load(function(e)
        {
			var data = eval("("+$("#fileIframe").contents().find("body").text()+")");
			if(data != null){ 
				if(data.result){
					var callback = data.callback;
					eval(callback+"(data)");
				}else{
					alert(data.message);
				}
			}
        });
		
		return null;
	}
	
}