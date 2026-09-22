![image](image_001.jpg)

차세대 부동산종합공부시스템 연계가이드
(WebService 표준연계)


# 2025. 04. 17.

국토교통부
공간정보제도과

제Ⅰ장 일반사항	 3

1. 목적	 3

2. 이용승인	 3

3. 규격 관리 주체	 3

4. 연계 방법	 3

제Ⅱ장 부동산종합증명서 조회 연계 규격	 4

1. 토지(임야) 대장	 4

2. 공유지연명부	 7

3. 토지(건물) 존재 여부 조회	 9

4. 대지권등록부(건물조회)	 11

5. 대지권등록부(전유부조회)	 13

6. 대지권등록부	 15

7. 토지이동연혁	 18

8. 소유권변동연혁	 20

9. 집합건물소유권연혁	 22

10. 토지이동내역	 24

11. 소유권변경내역	 26

12. 집합건물소유권변경내역	 28

13. 건물통합정보	 30

14. 건물통합도면	 34

15. SHAPE 다운로드	 35

16. 토지기본정보 다운로드	 36


# 목 차

| 제Ⅰ장 |  | 일반사항 |
| --- | --- | --- |

1. 목적

ㅇ 부동산종합공부시스템은 부동산종합증명서 조회, 일필지기본사항 조회를 제공한다.

| 연계 구분 | 정보 제공 범위 |
| --- | --- |
| 부동산종합증명서 조회 | 일필지, 일동, 일호의 소재지정보, 면적, 소유자, 층정보, 호정보 등의 정보 |
| 일필지기본사항 조회 | 일필지의 대장정보, 토지이동연혁, 공유인, 소유권변동연혁 등의 정보 |

ㅇ 이에 본 인터페이스 규격서는 해당기능의 연계 흐름과 방식을 정의 한다.

2. 이용승인

ㅇ 해당기능을 활용하기 위해서는 공간정보제도과 혹은 해당소관청에 이용승인을 득한 후 부동산종합공부시스템 사업단에 SYSTEM\_ID 생성을 요청하여야 하며, 이하 기능구현은 해당 규격서의 연계 지침을 따라야 한다.

3. 규격 관리 주체

ㅇ 본 규격은 부동산종합공부시스템 사업단에서 유지․관리한다.

4. 연계 방법

ㅇ 부동산종합공부시스템에서 제공된 wsdl의 정의와 parameter를 따라 각 자치단체별로 WebService 연계방식을 취한다. 요청에 따른 결과데이터는 사전등록된 요청기관의 GPKI인증서 및 BASE64로 암호화 된 XML형태로 제공한다.

| 제Ⅱ장 |  | 부동산종합증명서 조회 연계 규격 |
| --- | --- | --- |

1. 토지(임야)대장

□ 서비스 개요

| 서비스명 | 토지(임야) 대장 연계 |
| --- | --- |
| 서비스설명 | 토지(임야) 대장의 정보를 조회. 구 지적행정시스템에서는 일필지기본사항 이라는 이름으로 사용되기도 함 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th colspan="2">코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="8">In</td><td>-</td><td colspan="2">서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">행정구역코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td colspan="2">GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td colspan="2">소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">부번</td><td colspan="2"></td></tr>
<tr><td rowspan="40">Out</td><td>ADM_SECT_CD</td><td>행정구역코드</td><td>CHAR(5)</td><td rowspan="18">토지표시내역</td><td></td></tr>
<tr><td>LAND_LOC_CD</td><td>소재지코드</td><td>CHAR(5)</td><td></td></tr>
<tr><td>LEDG_GBN</td><td>대장구분</td><td>CHAR(1)</td><td></td></tr>
<tr><td>BOBN</td><td>본번</td><td>CHAR(4)</td><td></td></tr>
<tr><td>BUBN</td><td>부번</td><td>CHAR(4)</td><td></td></tr>
<tr><td>JIMOK</td><td>지목코드</td><td>CHAR(2)</td><td></td></tr>
<tr><td>JIMOK_NM</td><td>지목명</td><td>VARCHAR(150)</td><td></td></tr>
<tr><td>PAREA</td><td>면적</td><td>NUMBER(13.2)</td><td>예) 1,788</td></tr>
<tr><td>GRD</td><td>토지등급</td><td>VARCHAR(3)</td><td></td></tr>
<tr><td>GRD_YMD</td><td>등급변동일자</td><td>VARCHAR(8)</td><td></td></tr>
<tr><td>LAND_MOV_RSN_CD</td><td>이동사유코드</td><td>CHAR(2)</td><td></td></tr>
<tr><td>LAND_MOV_RSN_CD_NM</td><td>이동사유명</td><td>VARCHAR(150)</td><td></td></tr>
<tr><td>LAND_MOV_YMD</td><td>이동일자</td><td>VARCHAR(8)</td><td>예) 1986-11-10</td></tr>
<tr><td>LEDG_CNTRST_CNF_GBN</td><td>대장대조필구분</td><td>CHAR(1)</td><td></td></tr>
<tr><td>BIZ_ACT_NTC_GBN</td><td>사업시행신고구분</td><td>CHAR(1)</td><td></td></tr>
<tr><td>MAP_GBN</td><td>도해/수치 구분</td><td>VARCHAR(6)</td><td></td></tr>
<tr><td>LAND_LAST_HIST_ODRNO</td><td>토지최종연혁순번</td><td>CHAR(2)</td><td></td></tr>
<tr><td>OWN_RGT_LAST_HIST_ODRNO</td><td>소유권최종연혁순번</td><td>CHAR(4)</td><td></td></tr>
<tr><td>OWNER_NM</td><td>소유자명</td><td>VARCHAR(150)</td><td rowspan="9">토지소유내역</td><td rowspan="9">포함/불포함<br>옵션 가능</td></tr>
<tr><td>DREGNO</td><td>등록번호</td><td>VARCHAR(13)</td></tr>
<tr><td>OWN_GBN</td><td>소유구분코드</td><td>CHAR(2)</td></tr>
<tr><td>OWN_GBN_NM</td><td>소유구분명</td><td>VARCHAR(150)</td></tr>
<tr><td>SHR_CNT</td><td>공유인수</td><td>NUMBER(4)</td></tr>
<tr><td>OWNER_ADDR</td><td>주소</td><td>VARCHAR2(450)</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD</td><td>변동원인코드</td><td>CHAR(2)</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD_NM</td><td>변동원인명</td><td>VARCHAR(150)</td></tr>
<tr><td>OWNDYMD</td><td>변동일자</td><td>VARCHAR2(8)</td></tr>
<tr><td>SCALE</td><td>축척코드</td><td>CHAR(2)</td><td rowspan="13">토지기타정보</td><td></td></tr>
<tr><td>SCALE_NM</td><td>축척명</td><td>VARCHAR(150)</td><td>예) 1:600</td></tr>
<tr><td>DOHO</td><td>도호</td><td>VARCHAR2(3)</td><td></td></tr>
<tr><td>JIGA_BASE_MON</td><td>공시지가기준월</td><td>VARCHAR(7)</td><td></td></tr>
<tr><td>PANN_JIGA</td><td>공시지가</td><td>NUMBER(12)</td><td rowspan="5"></td></tr>
<tr><td>LAST_JIBN</td><td>최종종번</td><td>CHAR(8)</td></tr>
<tr><td>LAST_BU</td><td>본번의 최종부번</td><td>VARCHAR(4)</td></tr>
<tr><td>LASTBOBN</td><td>최종종번의 본번</td><td>VARCHAR(4)</td></tr>
<tr><td>LASTBUBN</td><td>최종종번의 부번</td><td>VARCHAR(4)</td></tr>
<tr><td>LAND_MOV_CHRG_MAN_ID</td><td>토지이동담당당자ID</td><td>VARCHAR2(20)</td><td></td></tr>
<tr><td>OWN_RGT_CHG_CHRG_MAN_ID</td><td>소유권변동담당자ID</td><td>VARCHAR2(20)</td><td></td></tr>
<tr><td>BLDG_GBN_NO</td><td>건물식별번호</td><td>NUMBER(28)</td><td></td></tr>
<tr><td>LAND_MOVE_RELL_JIBN</td><td>관련지번</td><td>VARCHAR2(4000)</td><td></td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<ADM\_SECT\_CD>27170</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10200</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>9999</BOBN>
<BUBN>9999</BUBN>
<JIMOK>08</JIMOK>
<JIMOK\_NM>대</JIMOK\_NM>
<PAREA>152</PAREA>
<GRD>227</GRD>
<GRD\_YMD/>
<LAND\_MOV\_RSN\_CD>50</LAND\_MOV\_RSN\_CD>
<LAND\_MOV\_RSN\_CD\_NM>대구광역시서구에서행정구역명칭변경</LAND\_MOV\_RSN\_CD\_NM>
<LAND\_MOV\_YMD/>
<LEDG\_CNTRST\_CNF\_GBN>1</LEDG\_CNTRST\_CNF\_GBN>
<BIZ\_ACT\_NTC\_GBN>0</BIZ\_ACT\_NTC\_GBN>
<MAP\_GBN>도해</MAP\_GBN>
<LAND\_LAST\_HIST\_ODRNO>02</LAND\_LAST\_HIST\_ODRNO>
<OWN\_RGT\_LAST\_HIST\_ODRNO>0005</OWN\_RGT\_LAST\_HIST\_ODRNO>
<OWNER\_NM/>
<DREGNO/>
<OWN\_GBN/>
<OWN\_GBN\_NM/>
<SHR\_CNT/>
<OWNER\_ADDR/>
<OWN\_RGT\_CHG\_RSN\_CD/>
<OWN\_RGT\_CHG\_RSN\_CD\_NM/>
<OWNDYMD/>
<SCALE>12</SCALE>
<SCALE\_NM>1:1200</SCALE\_NM>
<DOHO>009</DOHO>
<JIGA\_BASE\_MON>2013-01</JIGA\_BASE\_MON>
<PANN\_JIGA>788000</PANN\_JIGA>
<LAST\_JIBN>34430000</LAST\_JIBN>
<LAST\_BU>40</LAST\_BU>
<LASTBOBN>8888</LASTBOBN>
<LASTBUBN>0</LASTBUBN>
<LAND\_MOV\_CHRG\_MAN\_ID>22006900</LAND\_MOV\_CHRG\_MAN\_ID>
<OWN\_RGT\_CHG\_CHRG\_MAN\_ID>62121300</OWN\_RGT\_CHG\_CHRG\_MAN\_ID>
<BLDG\_GBN\_NO/>
<LAND\_MOVE\_RELL\_JIBN/>
</BODY>
</RESPONSE>

2. 공유지연명부

□ 서비스 개요

| 서비스명 | 공유지연명부 |
| --- | --- |
| 서비스설명 | 토지(임야)대장의 공유인 정보를 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="9">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>부번</td><td colspan="2"></td></tr>
<tr><td>-</td><td>말소자 포함유무</td><td colspan="2">* 예) Y, N</td></tr>
<tr><td rowspan="18">Out</td><td>SHR_YMB</td><td>공유인정보</td><td>-</td><td rowspan="18">공유인수만큼 반복</td></tr>
<tr><td>ADM_SECT_CD</td><td>행정구역코드</td><td rowspan="17">공유인 정보</td></tr>
<tr><td>LAND_LOC_CD</td><td>소재지코드</td></tr>
<tr><td>LEDG_GBN</td><td>대장구분</td></tr>
<tr><td>BOBN</td><td>본번</td></tr>
<tr><td>BUBN</td><td>부번</td></tr>
<tr><td>SHR_SEQNO</td><td>공유순번</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD</td><td>소유권변동원인</td></tr>
<tr><td>OWN_RGT_CHG_RSN_NM</td><td>소유권변동원인명</td></tr>
<tr><td>OWN_RGT_CHG_YMD</td><td>소유권변동일자</td></tr>
<tr><td>OWNER_REGNO</td><td>소유자등록번호</td></tr>
<tr><td>OWNER_NM</td><td>소유자명</td></tr>
<tr><td>OWNER_ADDR</td><td>소유자주소</td></tr>
<tr><td>OWN_RGT_JIBUN</td><td>소유권지분</td></tr>
<tr><td>OWN_RGT_CHG_DEL_YMD</td><td>소유권말소일자</td></tr>
<tr><td>OWN_GBN</td><td>소유구분</td></tr>
<tr><td>OWN_GBN_NM</td><td>소유구분명</td></tr>
<tr><td>PAREA</td><td>면적</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<SHR\_YMB\_SET>
<SHR\_YMB>
<ADM\_SECT\_CD>27170</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10400</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>9999</BOBN>
<BUBN>0000</BUBN>
<SHR\_SEQNO>000001</SHR\_SEQNO>
<OWN\_RGT\_CHG\_RSN\_CD>03</OWN\_RGT\_CHG\_RSN\_CD>
<OWN\_RGT\_CHG\_RSN\_NM>
소유권이전</OWN\_RGT\_CHG\_RSN\_NM>
<OWN\_RGT\_CHG\_YMD>19790725</OWN\_RGT\_CHG\_YMD>
<OWNER\_REGNO>\*\*\*\*\*\*\*\*\*\*\*\*\*</OWNER\_REGNO>
<OWNER\_NM>홍길동</OWNER\_NM>
<OWNER\_ADDR>138</OWNER\_ADDR>
<OWN\_RGT\_JIBUN>2/14</OWN\_RGT\_JIBUN>
<OWN\_GBN>01</OWN\_GBN>
<OWN\_GBN\_NM>개인</OWN\_GBN\_NM>
</SHR\_YMB>
<SHR\_YMB>
<ADM\_SECT\_CD>27170</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10400</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>0104</BOBN>
<BUBN>0000</BUBN>
<SHR\_SEQNO>000002</SHR\_SEQNO>
<OWN\_RGT\_CHG\_RSN\_CD>03</OWN\_RGT\_CHG\_RSN\_CD>
<OWN\_RGT\_CHG\_RSN\_NM>
소유권이전</OWN\_RGT\_CHG\_RSN\_NM>
<OWN\_RGT\_CHG\_YMD>19790725</OWN\_RGT\_CHG\_YMD>
<OWNER\_NM>이순신</OWNER\_NM>
<OWNER\_ADDR>중구 서성로1가 65</OWNER\_ADDR>
<OWN\_RGT\_JIBUN>1/14</OWN\_RGT\_JIBUN>
<OWN\_GBN>01</OWN\_GBN>
<OWN\_GBN\_NM>개인</OWN\_GBN\_NM>
</SHR\_YMB>
----------반복----------
</SHR\_YMB\_SET>
</BODY>
</RESPONSE>

3. 토지(건물) 존재 여부 조회

□ 서비스 개요

| 서비스명 | 토지(건물) 존재 여부 조회 |
| --- | --- |
| 서비스설명 | 토지 지번으로 토지 및 건물 존재 여부를 체크 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th colspan="2">코드명</th><th>비고</th></tr>
<tr><td rowspan="8">In</td><td>-</td><td colspan="2">서비스ID</td><td>*</td></tr>
<tr><td>-</td><td colspan="2">행정구역코드</td><td>* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td colspan="2">시스템코드</td><td>* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td colspan="2">GPKI인증서ID</td><td>옵션</td></tr>
<tr><td>-</td><td colspan="2">소재지코드</td><td>*</td></tr>
<tr><td>-</td><td colspan="2">대장구분</td><td>*</td></tr>
<tr><td>-</td><td colspan="2">본번</td><td>*</td></tr>
<tr><td>-</td><td colspan="2">부번</td><td>*</td></tr>
<tr><td rowspan="10">Out</td><td>REAL_GBN</td><td>토지_건물_구분</td><td>VARCHAR2<br>(100)</td><td>토지, 토지(건물)</td></tr>
<tr><td>ADM_SECT_CD</td><td>행정구역코드</td><td>CHAR(5)</td><td></td></tr>
<tr><td>LAND_LOC_CD</td><td>소재지코드</td><td>CHAR(5)</td><td></td></tr>
<tr><td>SECT_LOC_CD</td><td>시군구코드</td><td>CHAR(10)</td><td></td></tr>
<tr><td>ADM_SECT_NM</td><td>행정구역명</td><td>VARCHAR2<br>(300)</td><td></td></tr>
<tr><td>SECT_LOC_NM</td><td>소재지명</td><td>VARCHAR2<br>(300)</td><td></td></tr>
<tr><td>LEDG_GBN</td><td>대장구분</td><td>CHAR(1)</td><td></td></tr>
<tr><td>BOBN</td><td>본번</td><td>CHAR(4)</td><td></td></tr>
<tr><td>BUBN</td><td>부번</td><td>CHAR(4)</td><td></td></tr>
<tr><td>MAP_GBN</td><td>지적도 구분</td><td>CHAR(1)</td><td>1 : 지적도, 2 : 지적도,경계점좌표등록부</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_BLDG\_CHECK>
<REAL\_GBN>토지(건물)</REAL\_GBN>
<ADM\_SECT\_CD>27170</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10200</LAND\_LOC\_CD>
<SECT\_LOC\_CD>2717010200</SECT\_LOC\_CD>
<ADM\_SECT\_NM>서구</ADM\_SECT\_NM>
<SECT\_LOC\_NM>10200-비산동</SECT\_LOC\_NM>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>9999</BOBN>
<BUBN>9999</BUBN>
<MAP\_GBN/>
</LAND\_BLDG\_CHECK>
</BODY>
</RESPONSE>

4. 대지권등록부(건물조회)

□ 서비스 개요

| 서비스명 | 대지권등록부(건물정보) 연계 |
| --- | --- |
| 서비스설명 | 대지권등록부를 조회하기 위하여 건물정보(집합건물 고유코드)를 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th colspan="2">코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="8">In</td><td>-</td><td colspan="2">서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td colspan="2">시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td colspan="2">GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td colspan="2">소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">부번</td><td colspan="2">*</td></tr>
<tr><td rowspan="7">Out</td><td>ADM_SECT_CD</td><td>행정구역코드</td><td>CHAR(5)</td><td colspan="2"></td></tr>
<tr><td>LAND_LOC_CD</td><td>소재지코드</td><td>CHAR(5)</td><td colspan="2"></td></tr>
<tr><td>LEDG_GBN</td><td>대장구분</td><td>CHAR(1)</td><td colspan="2"></td></tr>
<tr><td>BOBN</td><td>본번</td><td>CHAR(4)</td><td colspan="2"></td></tr>
<tr><td>BUBN</td><td>부번</td><td>CHAR(4)</td><td colspan="2"></td></tr>
<tr><td>CBLDG_SEQNO</td><td>집합건물순번</td><td>CHAR(4)</td><td colspan="2"></td></tr>
<tr><td>CBLDG_NM</td><td>집합건물명</td><td>VARCHAR2(150)</td><td colspan="2"></td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_RGT\_BLDG\_INFO\_LIST>
<LAND\_RGT\_BLDG\_INFO>
<GAREA/>
<ADM\_SECT\_CD>44131</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10200</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>0001</BOBN>
<BUBN>0022</BUBN>
<CBLDG\_SEQNO>0018</CBLDG\_SEQNO>
<CBLDG\_NM>공동주택</CBLDG\_NM>
</LAND\_RGT\_BLDG\_INFO>
<LAND\_RGT\_BLDG\_INFO>
<GAREA/>
<ADM\_SECT\_CD>44131</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10200</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>0001</BOBN>
<BUBN>0022</BUBN>
<CBLDG\_SEQNO>0027</CBLDG\_SEQNO>
<CBLDG\_NM>공동주택</CBLDG\_NM>
</LAND\_RGT\_BLDG\_INFO>
</LAND\_RGT\_BLDG\_INFO\_LIST>
</BODY>
</RESPONSE>

5. 대지권등록부(전유부조회)

□ 서비스 개요

| 서비스명 | 대지권등록부(전유부) |
| --- | --- |
| 서비스설명 | 대지권 등록부의 전유부 정보(동층호실)를 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th colspan="2">코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="9">In</td><td>-</td><td colspan="2">서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td colspan="2">시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td colspan="2">GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td colspan="2">소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">부번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">집합건물순번</td><td colspan="2">*</td></tr>
<tr><td rowspan="8">Out</td><td>LAND_RGT_RFHS</td><td colspan="2">대지권등록부(전유부) 정보</td><td colspan="2"></td></tr>
<tr><td>CBLDG_SEQNO</td><td>집합건물순번</td><td>CHAR(4)</td><td colspan="2" rowspan="7">동층호실 수만큼 반복</td></tr>
<tr><td>DONG</td><td>집합건물_동</td><td>VARCHAR2(150)</td></tr>
<tr><td>CBLDG_NM</td><td>집합건물명</td><td>VARCHAR2(150)</td></tr>
<tr><td>FLR</td><td>집합건물_층</td><td>VARCHAR2(150)</td></tr>
<tr><td>HO</td><td>집합건물_호</td><td>VARCHAR2(150)</td></tr>
<tr><td>SIL</td><td>집합건물_실</td><td>VARCHAR2(150)</td></tr>
<tr><td>SHR_CNT</td><td>공유인수</td><td>NUMBER(10)</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_RGT\_RFHS\_LIST>
<LAND\_RGT\_RFHS>
<CBLDG\_SEQNO>27170</CBLDG\_SEQNO>
<DONG>10400</DONG>
<CBLDG\_NM>1</CBLDG\_NM>
<FLR>0104</FLR>
<HO>0000</HO>
<SIL>000001</SIL>
<SHR\_CNT>0</SHR\_CNT>
</LAND\_RGT\_RFHS>
<LAND\_RGT\_RFHS>
------------- 반복 ----------------------
</LAND\_RGT\_RFHS>
<LAND\_RGT\_RFHS\_LIST>
</BODY>
</RESPONSE>

6. 대지권등록부

□ 서비스 개요

| 서비스명 | 대지권등록부 |
| --- | --- |
| 서비스설명 | 집합건물의 대지에 관한 권리를 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th colspan="2">코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="14">In</td><td>-</td><td colspan="2">서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td colspan="2">시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td colspan="2">GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td colspan="2">소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">부번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">집합건물순번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">집합건물_동</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">집합건물_층</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">집합건물_호</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">집합건물_실</td><td colspan="2">*</td></tr>
<tr><td>-</td><td colspan="2">폐쇄구분</td><td colspan="2">0:현재, 1:폐쇄(분할), 9:폐쇄</td></tr>
<tr><td rowspan="33">Out</td><td>LAND_RGT</td><td colspan="2">대지권등록부</td><td>-</td><td rowspan="33">연혁개수만큼 반복</td></tr>
<tr><td>ADM_SECT_CD</td><td>행정구역코드</td><td>CHAR(5)</td><td rowspan="14">대지권부분</td></tr>
<tr><td>LAND_LOC_CD</td><td>소재지코드</td><td>CHAR(5)</td></tr>
<tr><td>LEDG_GBN</td><td>대장구분</td><td>CHAR(1)</td></tr>
<tr><td>BOBN</td><td>본번</td><td>CHAR(4)</td></tr>
<tr><td>BUBN</td><td>부번</td><td>CHAR(4)</td></tr>
<tr><td>CBLDG_SEQNO</td><td>집합건물순번</td><td>CHAR(4)</td></tr>
<tr><td>DONG</td><td>집합건물_동</td><td>VARCHAR2(150)</td></tr>
<tr><td>FLR</td><td>집합건물_층</td><td>VARCHAR2(150)</td></tr>
<tr><td>HO</td><td>집합건물_호</td><td>VARCHAR2(150)</td></tr>
<tr><td>SIL</td><td>집합건물_실</td><td>VARCHAR2(150)</td></tr>
<tr><td>CBLDG_NM</td><td>집합건물명</td><td>VARCHAR2(150)</td></tr>
<tr><td>LAND_RGT_JIBUN_RATE</td><td>대지권지분비율</td><td>VARCHAR2(150)</td></tr>
<tr><td>SHR_CNT</td><td>공유인수</td><td>NUMBER(10)</td></tr>
<tr><td>RELJIBN</td><td>관련지번</td><td>VARCHAR(12)</td></tr>
<tr><td>OWN_HIST</td><td>-</td><td></td><td>-</td></tr>
<tr><td>CBLDG_SEQNO</td><td>집합건물순번</td><td>CHAR(4)</td><td rowspan="17">소유권연혁</td></tr>
<tr><td>DONG</td><td>집합건물_동</td><td>VARCHAR2(150)</td></tr>
<tr><td>FLR</td><td>집합건물_층</td><td>VARCHAR2(150)</td></tr>
<tr><td>HO</td><td>집합건물_호</td><td>VARCHAR2(150)</td></tr>
<tr><td>SIL</td><td>집합건물_실</td><td>VARCHAR2(150)</td></tr>
<tr><td>CBLDG_NM</td><td>집합건물 명</td><td>VARCHAR2(150)</td></tr>
<tr><td>OWN_RGT_HIST_ODRNO</td><td>소유권연혁순번</td><td>CHAR(4)</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD</td><td>소유권변동원인</td><td>CHAR(2)</td></tr>
<tr><td>OWN_RGT_CHG_RSN_NM</td><td>소유권변동원인명</td><td>VARCHAR2(150)</td></tr>
<tr><td>OWN_RGT_CHG_YMD</td><td>소유권변동일자</td><td>VARCHAR2(8)</td></tr>
<tr><td>OWN_RGT_JIBUN</td><td>소유권지분</td><td>VARCHAR2(150)</td></tr>
<tr><td>OWNER_REGNO</td><td>소유자등록번호</td><td>VARCHAR2(13)</td></tr>
<tr><td>OWNER_NM</td><td>소유자명</td><td>VARCHAR2(150)</td></tr>
<tr><td>OWNER_ADDR</td><td>소유자주소</td><td>VARCHAR2(450)</td></tr>
<tr><td>OWN_GBN</td><td>소유구분</td><td>CHAR(2)</td></tr>
<tr><td>OWN_GBN_NM</td><td>소유구분명</td><td>VARCHAR2(150)</td></tr>
<tr><td>CHRG_MAN_ID</td><td>소유권변동담당자ID</td><td>VARCHAR2(20)</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_RGT\_SET>
<LAND\_RGT>
<ADM\_SECT\_CD>27170</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10200</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>9999</BOBN>
<BUBN>0046</BUBN>
<CBLDG\_SEQNO>0285</CBLDG\_SEQNO>
<DONG></DONG>
<FLR>1</FLR>
<HO>102</HO>
<SIL></SIL>
<CBLDG\_NM>다세대주택</CBLDG\_NM>
<LAND\_RGT\_JIBUN\_RATE>14/112</LAND\_RGT\_JIBUN\_RATE>
<SHR\_CNT>2</SHR\_CNT>
<RELJIBN>0504-0046</RELJIBN>
</LAND\_RGT>
<LAND\_RGT> ----------반복---------- </LAND\_RGT>
<OWN\_HIST>
<CBLDG\_SEQNO>0285</CBLDG\_SEQNO>
<DONG></DONG>
<FLR>1</FLR>
<HO>102</HO>
<SIL></SIL>
<CBLDG\_NM/>
<OWN\_RGT\_HIST\_ODRNO>0001</OWN\_RGT\_HIST\_ODRNO>
<OWN\_RGT\_CHG\_RSN\_CD>02</OWN\_RGT\_CHG\_RSN\_CD>
<OWN\_RGT\_CHG\_RSN\_NM>소유권보존</OWN\_RGT\_CHG\_RSN\_NM>
<OWN\_RGT\_CHG\_YMD>20001129</OWN\_RGT\_CHG\_YMD>
<OWN\_RGT\_JIBUN/>
<OWNER\_REGNO>\*\*\*\*\*</OWNER\_REGNO>
<OWNER\_NM>\*\*\*\*\*</OWNER\_NM>
<OWNER\_ADDR>\*\*\*\*\*</OWNER\_ADDR>
<OWN\_GBN>01</OWN\_GBN>
<OWN\_GBN\_NM>개인</OWN\_GBN\_NM>
<CHRG\_MAN\_ID>rose</CHRG\_MAN\_ID>
</OWN\_HIST>
<OWN\_HIST> ----------반복---------- </OWN\_HIST>
</LAND\_RGT\_SET>
</BODY>
</RESPONSE>

7. 토지이동연혁

□ 서비스 개요

| 서비스명 | 토지이동연혁 연계 |
| --- | --- |
| 서비스설명 | 토지의 이동연혁을 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="8">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>부번</td><td colspan="2">*</td></tr>
<tr><td rowspan="19">Out</td><td>LAND_MOV_HIST</td><td>토지이동연혁</td><td>-</td><td rowspan="17">연혁 개수만큼 반복</td></tr>
<tr><td>JIMOK</td><td>지목</td><td rowspan="16">토지이동연혁</td></tr>
<tr><td>LAND_MOV_RSN_CD</td><td>토지이동사유코드</td></tr>
<tr><td>SCALE</td><td>스케일</td></tr>
<tr><td>OWN_GBN</td><td>소유구분</td></tr>
<tr><td>SHR_CNT</td><td>공유인수</td></tr>
<tr><td>SCALE_NM</td><td>스케일명</td></tr>
<tr><td>DOHO</td><td>도호</td></tr>
<tr><td>DEL_YMD</td><td>말소일자</td></tr>
<tr><td>LAND_MOV_DEL_YMD</td><td>토지이동말소일자</td></tr>
<tr><td>LAND_MOV_HIST_ODRNO</td><td>토지이동연혁순번</td></tr>
<tr><td>LAND_HIST_ODRNO</td><td>연혁순번</td></tr>
<tr><td>JIMOK_NM</td><td>지목명</td></tr>
<tr><td>PAREA</td><td>면적</td></tr>
<tr><td>DYMD</td><td>이동일자</td></tr>
<tr><td>LAND_MOV_RSN_CD_NM</td><td>토지이동사유명</td></tr>
<tr><td>LAND_MOV_CHRG_MAN_ID</td><td>처리담당자</td></tr>
<tr><td>RELJIBUN</td><td>관련지번</td><td>-</td><td rowspan="2">관련지번 개수만큼 반복</td></tr>
<tr><td>JIBUN</td><td>지번</td><td>관련지번</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_MOV\_HIST\_SET>
<LAND\_MOV\_HIST>
<JIMOK>08</JIMOK>
<LAND\_MOV\_RSN\_CD>20</LAND\_MOV\_RSN\_CD>
<SCALE>12</SCALE>
<OWN\_GBN/>
<SHR\_CNT>0</SHR\_CNT>
<OWNER\_ADDR/>
<OWNER\_NM/>
<SCALE\_NM>1:1200</SCALE\_NM>
<DOHO>008</DOHO>
<DEL\_YMD/>
<LAND\_MOV\_DEL\_YMD>19950101</LAND\_MOV\_DEL\_YMD>
<LAND\_MOV\_HIST\_ODRNO>1882697</LAND\_MOV\_HIST\_ODRNO>
<LAND\_HIST\_ODRNO>01</LAND\_HIST\_ODRNO>
<JIMOK\_NM>대</JIMOK\_NM>
<PAREA>10</PAREA>
<DYMD>1957-05-18</DYMD>
<LAND\_MOV\_RSN\_CD\_NM>
분할되어 본번에 -4내지 -10부함</LAND\_MOV\_RSN\_CD\_NM>
<LAND\_MOV\_CHRG\_MAN\_ID>00000000</LAND\_MOV\_CHRG\_MAN\_ID>
<RELJIBUN>
<JIBUN>999-9</JIBUN>
</RELJIBUN>
</LAND\_MOV\_HIST>
<LAND\_MOV\_HIST>
----------반복----------
</LAND\_MOV\_HIST>
</LAND\_MOV\_HIST\_SET>
</BODY>
</RESPONSE>

8. 소유권변동연혁

□ 서비스 개요

| 서비스명 | 소유권변동연혁 연계 |
| --- | --- |
| 서비스설명 | 토지(임야)대장의 소유권 변동연혁을 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="8">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>부번</td><td colspan="2"></td></tr>
<tr><td rowspan="17">Out</td><td>OWN_RGT_HIST</td><td>소유권변동연혁</td><td>-</td><td rowspan="17">연혁 개수만큼 반복</td></tr>
<tr><td>ADM_SECT_CD</td><td>행정구역코드</td><td rowspan="16">소유권변동연혁</td></tr>
<tr><td>LAND_LOC_CD</td><td>소재지코드</td></tr>
<tr><td>LEDG_GBN</td><td>대장구분</td></tr>
<tr><td>BOBN</td><td>본번</td></tr>
<tr><td>BUBN</td><td>부번</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD</td><td>소유권변동원인코드</td></tr>
<tr><td>DREGNO</td><td>등록번호</td></tr>
<tr><td>DYMD</td><td>변동일자</td></tr>
<tr><td>OWNER_NM</td><td>소유자명</td></tr>
<tr><td>SHR_CNT</td><td>공유인수</td></tr>
<tr><td>OWN_GBN</td><td>소유구분</td></tr>
<tr><td>OWN_RGT_CHG_CHRG_MAN_ID</td><td>처리담당자</td></tr>
<tr><td>DODRNO</td><td>연혁순번</td></tr>
<tr><td>OWN_RGT_CHG_HIST_ODRNO</td><td>소유권연혁순번</td></tr>
<tr><td>OWN_GBN_NM</td><td>소유구분명</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD_NM</td><td>변동원인명</td></tr>
</table>

□ 결과 XML

<?xml version="1.0" encoding="UTF-8"?>
<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<OWN\_RGT\_HIST\_SET>
<OWN\_RGT\_HIST>
<ADM\_SECT\_CD>27170</ADM\_SECT\_CD>
<LAND\_LOC\_CD>10200</LAND\_LOC\_CD>
<LEDG\_GBN>1</LEDG\_GBN>
<BOBN>9999</BOBN>
<BUBN>0036</BUBN>
<OWN\_RGT\_CHG\_RSN\_CD>05</OWN\_RGT\_CHG\_RSN\_CD>
<DREGNO>\*\*\*</DREGNO>
<DYMD>20131206</DYMD>
<OWNER\_NM>국(국토교통부)</OWNER\_NM>
<SHR\_CNT>0</SHR\_CNT>
<OWN\_GBN>02</OWN\_GBN>
<OWN\_RGT\_CHG\_CHRG\_MAN\_ID>80020900</OWN\_RGT\_CHG\_CHRG\_MAN\_ID>
<DODRNO>003</DODRNO>
<OWN\_RGT\_CHG\_HIST\_ODRNO>2464036</OWN\_RGT\_CHG\_HIST\_ODRNO>
<OWN\_GBN\_NM>국유지</OWN\_GBN\_NM>
<OWN\_RGT\_CHG\_RSN\_CD\_NM>성명(명칭)변경</OWN\_RGT\_CHG\_RSN\_CD\_NM>
</OWN\_RGT\_HIST>
<OWN\_RGT\_HIST>
----------반복----------
</OWN\_RGT\_HIST>
</OWN\_RGT\_HIST\_SET>
</BODY>
</RESPONSE>

9. 집합건물소유권연혁

□ 서비스 개요

| 서비스명 | 집합건물소유권연혁 연계 |
| --- | --- |
| 서비스설명 | 집합건물(대지권)의 소유권 연혁을 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th>비고</th></tr>
<tr><td rowspan="10">In</td><td>-</td><td>서비스ID</td><td>*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td>* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td>* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td>옵션</td></tr>
<tr><td>-</td><td>소재지코드</td><td>*</td></tr>
<tr><td>-</td><td>집합건물일련번호</td><td>*</td></tr>
<tr><td>-</td><td>동</td><td>*</td></tr>
<tr><td>-</td><td>층</td><td>*</td></tr>
<tr><td>-</td><td>호</td><td>*</td></tr>
<tr><td>-</td><td>실</td><td>*</td></tr>
<tr><td rowspan="17">Out</td><td>CBLDG_SEQNO</td><td>집합건물일련번호</td><td rowspan="17">개수만큼 반복</td></tr>
<tr><td>DONG</td><td>동</td></tr>
<tr><td>FLR</td><td>층</td></tr>
<tr><td>HO</td><td>호</td></tr>
<tr><td>SIL</td><td>실</td></tr>
<tr><td>OWN_RGT_HIST_ODRNO</td><td>소유권연혁순번</td></tr>
<tr><td>OWNER_NM</td><td>소유자명</td></tr>
<tr><td>DREGNO</td><td>등록번호</td></tr>
<tr><td>OWN_RGT_CHG_RSN_CD</td><td>소유권변경원인코드</td></tr>
<tr><td>OWN_RGT_CHG_RSN_NM</td><td>소유권변경원인명</td></tr>
<tr><td>OWN_RGT_CHG_YMD</td><td>소유권변경일자</td></tr>
<tr><td>CHRG_MAN_ID</td><td>담당자ID</td></tr>
<tr><td>OWN_GBN</td><td>소유구분</td></tr>
<tr><td>OWN_GBN_NM</td><td>소유구분명</td></tr>
<tr><td>OWN_RGT_JIBUN</td><td>소유권지분</td></tr>
<tr><td>OWNER_ADDR</td><td>소유자주소</td></tr>
<tr><td>DEL_YMD</td><td>말소일자</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<CBLDG\_OWN\_HIST\_SET>
<CBLDG\_OWN\_HIST>
<CBLDG\_SEQNO>0018</CBLDG\_SEQNO>
<DONG/>
<FLR>1</FLR>
<HO>102</HO>
<SIL/>
<OWN\_RGT\_HIST\_ODRNO>0004</OWN\_RGT\_HIST\_ODRNO>
<OWNER\_NM>\*\*\*\*\*</OWNER\_NM>
<DREGNO>\*\*\*\*\*</DREGNO>
<OWN\_RGT\_CHG\_RSN\_CD>03</OWN\_RGT\_CHG\_RSN\_CD>
<OWN\_RGT\_CHG\_RSN\_NM>소유권이전</OWN\_RGT\_CHG\_RSN\_NM>
<OWN\_RGT\_CHG\_YMD>20091006</OWN\_RGT\_CHG\_YMD>
<CHRG\_MAN\_ID>YEON0001</CHRG\_MAN\_ID>
<OWN\_GBN>01</OWN\_GBN>
<OWN\_GBN\_NM>개인</OWN\_GBN\_NM>
<OWN\_RGT\_JIBUN/>
<OWNER\_ADDR>\*\*\*\*\*</OWNER\_ADDR>
<DEL\_YMD/>
</CBLDG\_OWN\_HIST>
<CBLDG\_OWN\_HIST>
----------반복----------
</CBLDG\_OWN\_HIST>
</CBLDG\_OWN\_HIST\_SET>
</BODY>
</RESPONSE>

10. 토지이동 변동내역

□ 서비스 개요

| 서비스명 | 토지이동내역 연계 |
| --- | --- |
| 서비스설명 | 특정 기간 내 토지이동 내역을 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="6">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>처리일자(시작일)</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>처리일자(종료일)</td><td colspan="2">* 시작일 기준 10일 내 제공</td></tr>
<tr><td rowspan="20">Out</td><td>LAND_MOV_CHG</td><td>토지이동내역</td><td>-</td><td rowspan="20">토지이동 내역 개수만큼 반복</td></tr>
<tr><td>LAND_MOV_NO</td><td>토지이동순번</td><td rowspan="19">토지이동내역</td></tr>
<tr><td>LAND_MOV_NM</td><td>토지이동</td></tr>
<tr><td>LAND_MOV_ITEM</td><td>토지이동종목</td></tr>
<tr><td>BF_LAND_LOC_CD</td><td>이동전 소재지코드</td></tr>
<tr><td>BF_LEDG_GBN</td><td>이동전 대장구분</td></tr>
<tr><td>BF_BOBN</td><td>이동전 본번</td></tr>
<tr><td>BF_BUBN</td><td>이동전 부번</td></tr>
<tr><td>BF_JIMOK</td><td>이동전 지목</td></tr>
<tr><td>BF_PAREA</td><td>이동전 면적</td></tr>
<tr><td>AF_LAND_LOC_CD</td><td>이동후 소재지코드</td></tr>
<tr><td>AF_LEDG_GBN</td><td>이동후 대장구분</td></tr>
<tr><td>AF_BOBN</td><td>이동후 본번</td></tr>
<tr><td>AF_BUBN</td><td>이동후 부번</td></tr>
<tr><td>AF_JIMOK</td><td>이동후 지목</td></tr>
<tr><td>AF_PAREA</td><td>이동후 면적</td></tr>
<tr><td>LAND_MOV_RSN_CD</td><td>토지이동사유코드</td></tr>
<tr><td>LAND_MOV_RSN_NM</td><td>토지이동사유명</td></tr>
<tr><td>ADJ_YMD</td><td>정리일자</td></tr>
<tr><td>HNDL_YMD</td><td>처리일자</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_MOV\_CHG\_SET>
<LAND\_MOV\_CHG>
<LAND\_MOV\_NO>27170012014200001800010001</LAND\_MOV\_NO>
<LAND\_MOV\_NM>분할(토지대장)</LAND\_MOV\_NM>
<LAND\_MOV\_ITEM>20</LAND\_MOV\_ITEM>
<BF\_LAND\_LOC\_CD>10700</BF\_LAND\_LOC\_CD>
<BF\_LEDG\_GBN>1</BF\_LEDG\_GBN>
<BF\_BOBN>9999</BF\_BOBN>
<BF\_BUBN>0000</BF\_BUBN>
<BF\_JIMOK>09</BF\_JIMOK>
<BF\_PAREA>763</BF\_PAREA>
<AF\_LAND\_LOC\_CD>10700</AF\_LAND\_LOC\_CD>
<AF\_LEDG\_GBN>1</AF\_LEDG\_GBN>
<AF\_BOBN>8888</AF\_BOBN>
<AF\_BUBN>0000</AF\_BUBN>
<AF\_JIMOK>09</AF\_JIMOK>
<AF\_PAREA>272</AF\_PAREA>
<LAND\_MOV\_RSN\_CD>20</LAND\_MOV\_RSN\_CD>
<LAND\_MOV\_RSN\_NM>분할되어 본번에 을 부함</LAND\_MOV\_RSN\_NM>
<ADJ\_YMD>20140801</ADJ\_YMD>
<HNDL\_YMD>20140801</HNDL\_YMD>
</LAND\_MOV\_CHG>
<LAND\_MOV\_CHG>
----------반복----------
</LAND\_MOV\_CHG>
</LAND\_MOV\_CHG\_SET
</BODY>
</RESPONSE>

11. 소유권 변동내역

□ 서비스 개요

| 서비스명 | 소유권 변동내역 |
| --- | --- |
| 서비스설명 | 특정 기간 내 소유권 변동내역을 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="6">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>처리일자(시작일)</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>처리일자(종료일)</td><td colspan="2">* 시작일 기준 10일 내 제공</td></tr>
<tr><td rowspan="20">Out</td><td>LAND_MOV_CHG</td><td>토지이동내역</td><td>-</td><td rowspan="20">토지이동 내역 개수만큼 반복</td></tr>
<tr><td>LAND_MOV_NO</td><td>토지이동순번</td><td rowspan="19">토지이동내역</td></tr>
<tr><td>LAND_MOV_NM</td><td>토지이동</td></tr>
<tr><td>LAND_MOV_ITEM</td><td>토지이동종목</td></tr>
<tr><td>BF_LAND_LOC_CD</td><td>이동전 소재지코드</td></tr>
<tr><td>BF_LEDG_GBN</td><td>이동전 대장구분</td></tr>
<tr><td>BF_BOBN</td><td>이동전 본번</td></tr>
<tr><td>BF_BUBN</td><td>이동전 부번</td></tr>
<tr><td>BF_JIMOK</td><td>이동전 지목</td></tr>
<tr><td>BF_PAREA</td><td>이동전 면적</td></tr>
<tr><td>AF_LAND_LOC_CD</td><td>이동후 소재지코드</td></tr>
<tr><td>AF_LEDG_GBN</td><td>이동후 대장구분</td></tr>
<tr><td>AF_BOBN</td><td>이동후 본번</td></tr>
<tr><td>AF_BUBN</td><td>이동후 부번</td></tr>
<tr><td>AF_JIMOK</td><td>이동후 지목</td></tr>
<tr><td>AF_PAREA</td><td>이동후 면적</td></tr>
<tr><td>LAND_MOV_RSN_CD</td><td>토지이동사유코드</td></tr>
<tr><td>LAND_MOV_RSN_NM</td><td>토지이동사유명</td></tr>
<tr><td>ADJ_YMD</td><td>정리일자</td></tr>
<tr><td>HNDL_YMD</td><td>처리일자</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_MOV\_CHG\_SET>
<LAND\_MOV\_CHG>
<LAND\_MOV\_NO>27170012014200001800010001</LAND\_MOV\_NO>
<LAND\_MOV\_NM>분할(토지대장)</LAND\_MOV\_NM>
<LAND\_MOV\_ITEM>20</LAND\_MOV\_ITEM>
<BF\_LAND\_LOC\_CD>10700</BF\_LAND\_LOC\_CD>
<BF\_LEDG\_GBN>1</BF\_LEDG\_GBN>
<BF\_BOBN>9999</BF\_BOBN>
<BF\_BUBN>0000</BF\_BUBN>
<BF\_JIMOK>09</BF\_JIMOK>
<BF\_PAREA>763</BF\_PAREA>
<AF\_LAND\_LOC\_CD>10700</AF\_LAND\_LOC\_CD>
<AF\_LEDG\_GBN>1</AF\_LEDG\_GBN>
<AF\_BOBN>8888</AF\_BOBN>
<AF\_BUBN>0000</AF\_BUBN>
<AF\_JIMOK>09</AF\_JIMOK>
<AF\_PAREA>272</AF\_PAREA>
<LAND\_MOV\_RSN\_CD>20</LAND\_MOV\_RSN\_CD>
<LAND\_MOV\_RSN\_NM>분할되어 본번에 을 부함</LAND\_MOV\_RSN\_NM>
<ADJ\_YMD>20140801</ADJ\_YMD>
<HNDL\_YMD>20140801</HNDL\_YMD>
</LAND\_MOV\_CHG>
<LAND\_MOV\_CHG>
----------반복----------
</LAND\_MOV\_CHG>
</LAND\_MOV\_CHG\_SET
</BODY>
</RESPONSE>

12. 집합건물소유권 변동내역

□ 서비스 개요

| 서비스명 | 집합건물소유권 변동내역 |
| --- | --- |
| 서비스설명 | 특정 기간 내 집합건물(대지권) 소유권변동 내역 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="6">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>처리일자(시작일)</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>처리일자(종료일)</td><td colspan="2">*</td></tr>
<tr><td rowspan="20">Out</td><td>LAND_MOV_CHG</td><td>토지이동내역</td><td>-</td><td rowspan="20">토지이동 내역 개수만큼 반복</td></tr>
<tr><td>LAND_MOV_NO</td><td>토지이동순번</td><td rowspan="19">토지이동내역</td></tr>
<tr><td>LAND_MOV_NM</td><td>토지이동</td></tr>
<tr><td>LAND_MOV_ITEM</td><td>토지이동종목</td></tr>
<tr><td>BF_LAND_LOC_CD</td><td>이동전 소재지코드</td></tr>
<tr><td>BF_LEDG_GBN</td><td>이동전 대장구분</td></tr>
<tr><td>BF_BOBN</td><td>이동전 본번</td></tr>
<tr><td>BF_BUBN</td><td>이동전 부번</td></tr>
<tr><td>BF_JIMOK</td><td>이동전 지목</td></tr>
<tr><td>BF_PAREA</td><td>이동전 면적</td></tr>
<tr><td>AF_LAND_LOC_CD</td><td>이동후 소재지코드</td></tr>
<tr><td>AF_LEDG_GBN</td><td>이동후 대장구분</td></tr>
<tr><td>AF_BOBN</td><td>이동후 본번</td></tr>
<tr><td>AF_BUBN</td><td>이동후 부번</td></tr>
<tr><td>AF_JIMOK</td><td>이동후 지목</td></tr>
<tr><td>AF_PAREA</td><td>이동후 면적</td></tr>
<tr><td>LAND_MOV_RSN_CD</td><td>토지이동사유코드</td></tr>
<tr><td>LAND_MOV_RSN_NM</td><td>토지이동사유명</td></tr>
<tr><td>ADJ_YMD</td><td>정리일자</td></tr>
<tr><td>HNDL_YMD</td><td>처리일자</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<LAND\_MOV\_CHG\_SET>
<LAND\_MOV\_CHG>
<LAND\_MOV\_NO>27170012014200001800010001</LAND\_MOV\_NO>
<LAND\_MOV\_NM>분할(토지대장)</LAND\_MOV\_NM>
<LAND\_MOV\_ITEM>20</LAND\_MOV\_ITEM>
<BF\_LAND\_LOC\_CD>10700</BF\_LAND\_LOC\_CD>
<BF\_LEDG\_GBN>1</BF\_LEDG\_GBN>
<BF\_BOBN>9999</BF\_BOBN>
<BF\_BUBN>0000</BF\_BUBN>
<BF\_JIMOK>09</BF\_JIMOK>
<BF\_PAREA>763</BF\_PAREA>
<AF\_LAND\_LOC\_CD>10700</AF\_LAND\_LOC\_CD>
<AF\_LEDG\_GBN>1</AF\_LEDG\_GBN>
<AF\_BOBN>8888</AF\_BOBN>
<AF\_BUBN>0000</AF\_BUBN>
<AF\_JIMOK>09</AF\_JIMOK>
<AF\_PAREA>272</AF\_PAREA>
<LAND\_MOV\_RSN\_CD>20</LAND\_MOV\_RSN\_CD>
<LAND\_MOV\_RSN\_NM>분할되어 본번에 을 부함</LAND\_MOV\_RSN\_NM>
<ADJ\_YMD>20140801</ADJ\_YMD>
<HNDL\_YMD>20140801</HNDL\_YMD>
</LAND\_MOV\_CHG>
<LAND\_MOV\_CHG>
----------반복----------
</LAND\_MOV\_CHG>
</LAND\_MOV\_CHG\_SET
</BODY>
</RESPONSE>

13. 건물통합정보

□ 서비스 개요

| 서비스명 | 건물통합정보 연계 |
| --- | --- |
| 서비스설명 | 토지(임야)대장 정보와와 건축행정시스템(세움터)의 건축물대장 속성정보를 건물단위로 통합하여 구축한 토지 기반의 건물통합정보를 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th colspan="2">비고</th></tr>
<tr><td rowspan="8">In</td><td>-</td><td>서비스ID</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td colspan="2">* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td colspan="2">* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td colspan="2">옵션</td></tr>
<tr><td>-</td><td>소재지코드</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>대장구분</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>본번</td><td colspan="2">*</td></tr>
<tr><td>-</td><td>부번</td><td colspan="2"></td></tr>
<tr><td rowspan="50">Out</td><td>GIS_BLDG_INTERG_INFO</td><td>건물통합정보 정보</td><td>-</td><td rowspan="48">건물통합정보 개수만큼 반복</td></tr>
<tr><td>LAND_LOC_NM</td><td>소재지명</td><td rowspan="47">건물통합정보 기본정보</td></tr>
<tr><td>JIBN</td><td>지번</td></tr>
<tr><td>PNU</td><td>PNU</td></tr>
<tr><td>UFID</td><td>UFID</td></tr>
<tr><td>BLDG_NM</td><td>건물명</td></tr>
<tr><td>DONG</td><td>동</td></tr>
<tr><td>LAREA</td><td>대지면적</td></tr>
<tr><td>BAREA</td><td>건축면적</td></tr>
<tr><td>GAREA</td><td>연면적</td></tr>
<tr><td>BLR</td><td>건폐율</td></tr>
<tr><td>FSI</td><td>용적율</td></tr>
<tr><td>VIO_BLDG_YN</td><td>위반건물여부</td></tr>
<tr><td>UFLR</td><td>지상층수</td></tr>
<tr><td>BFLR</td><td>지하층수</td></tr>
<tr><td>HGT</td><td>높이</td></tr>
<tr><td>STRU_CD</td><td>주구조코드</td></tr>
<tr><td>STRU_NM</td><td>주구조</td></tr>
<tr><td>MAIN_USE_CD</td><td>주용도코드</td></tr>
<tr><td>MAIN_USE_NM</td><td>주용도명</td></tr>
<tr><td>USE_APRV_YMD</td><td>승인일자</td></tr>
<tr><td>BLDG_GBN_NO</td><td>건물구분번호</td></tr>
<tr><td>MAIN_SUB_GBN</td><td>주/부건축물코드</td></tr>
<tr><td>MAIN_SUB_GBN_NM</td><td>주/부건축물명</td></tr>
<tr><td>REGIST_DAY</td><td>데이터생성(변경)일자</td></tr>
<tr><td>BNDR_INFO_SRC_CD</td><td>공간정보출처코드</td></tr>
<tr><td>BNDR_INFO_SRC_NM</td><td>공간정보출처명</td></tr>
<tr><td>ATTR_INFO_SRC_CD</td><td>속성정보출처코드</td></tr>
<tr><td>ATTR_INFO_SRC_NM</td><td>속성정보출처명</td></tr>
<tr><td>KM_NAME</td><td>기타건축물명</td></tr>
<tr><td>KM_NAME_SRC</td><td>기타건축물 출처코드</td></tr>
<tr><td>KM_NAME_SRC_NM</td><td>기타건축물 출처</td></tr>
<tr><td>PERMI_NUM</td><td>건축허가번호</td></tr>
<tr><td>USE_APR_NUM</td><td>공용건축물승인번호</td></tr>
<tr><td>ETC_CD</td><td>대상외건물코드</td></tr>
<tr><td>ETC_CD_NM</td><td>대상외건물</td></tr>
<tr><td>CH_JIBUN</td><td>지번변경검토코드</td></tr>
<tr><td>CH_JIBUN_NM</td><td>지번변경검토</td></tr>
<tr><td>MAT_CD</td><td>매칭유형코드</td></tr>
<tr><td>S_MAT</td><td>매칭기준코드</td></tr>
<tr><td>S_MAT_NM</td><td>매칭기준명</td></tr>
<tr><td>BLD_CNT</td><td>건물수</td></tr>
<tr><td>AIS_CNT</td><td>대장수</td></tr>
<tr><td>NEM_DATE</td><td>수치지형도 제작일</td></tr>
<tr><td>PNU_ORG</td><td>원지번</td></tr>
<tr><td>BU_MAT_GB_CD</td><td>매칭유형 세부코드</td></tr>
<tr><td>BU_MAT_GB_NM</td><td>매칭유형 세부정보</td></tr>
<tr><td>SUB_INFO_CNT</td><td></td></tr>
<tr><td>RELJIBUN</td><td>관련지번</td><td>-</td><td rowspan="2">관련지번 만큼 반복</td></tr>
<tr><td>REL_JIBUN</td><td>관련지번</td><td>관련지번</td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<GIS\_BLDG\_INTERG\_INFO\_SET>
<GIS\_BLDG\_INTERG\_INFO>
<LAND\_LOC\_NM>대한동</LAND\_LOC\_NM>
<JIBN>0315-0000</JIBN>
<PNU>2717010200199990000</PNU>
<UFID>2005161015182633999900000000</UFID>
<BLDG\_NM>대한 아파트</BLDG\_NM>
<DONG>101동</DONG>
<LAREA>14,085</LAREA>
<BAREA>1,465.7891</BAREA>
<GAREA>20,879.7479</GAREA>
<BLR>10.999999999</BLR>
<FSI>148.999999999</FSI>
<VIO\_BLDG\_YN>N</VIO\_BLDG\_YN>
<UFLR>19</UFLR>
<BFLR>0</BFLR>
<HGT>51</HGT>
<STRU\_CD>21</STRU\_CD>
<STRU\_NM>철근콘크리트구조</STRU\_NM>
<MAIN\_USE\_CD>02000</MAIN\_USE\_CD>
<MAIN\_USE\_NM>공동주택</MAIN\_USE\_NM>
<USE\_APRV\_YMD>2005-12-12</USE\_APRV\_YMD>
<BLDG\_GBN\_NO>17864</BLDG\_GBN\_NO>
<MAIN\_SUB\_GBN>0</MAIN\_SUB\_GBN>
<MAIN\_SUB\_GBN\_NM>주건축물</MAIN\_SUB\_GBN\_NM>
<REGIST\_DAY>2010-01-13</REGIST\_DAY>
<BNDR\_INFO\_SRC\_CD>0102</BNDR\_INFO\_SRC\_CD>
<BNDR\_INFO\_SRC\_NM>수치지형도(1/5,000)</BNDR\_INFO\_SRC\_NM>
<ATTR\_INFO\_SRC\_CD>02</ATTR\_INFO\_SRC\_CD>
<ATTR\_INFO\_SRC\_NM>건축물대장기초자료(세움터)</ATTR\_INFO\_SRC\_NM>
<KM\_NAME>대한 아파트 9999동</KM\_NAME>
<MAT\_CD>AM00</MAT\_CD>
<S\_MAT>A</S\_MAT>
<S\_MAT\_NM>건물명으로 매칭</S\_MAT\_NM>
<BLD\_CNT>7</BLD\_CNT>
<AIS\_CNT>11</AIS\_CNT>
<NEM\_DATE>2007-12-31</NEM\_DATE>
<SUB\_INFO\_CNT>0</SUB\_INFO\_CNT>
</GIS\_BLDG\_INTERG\_INFO>
<GIS\_BLDG\_INTERG\_INFO>
----------반복----------
</GIS\_BLDG\_INTERG\_INFO>
</GIS\_BLDG\_INTERG\_INFO\_SET>
</BODY>
</RESPONSE>

14. 건물통합도면

□ 서비스 개요

| 서비스명 | 건물통합도면 연계 |
| --- | --- |
| 서비스설명 | 연속지적도형정보를 기반으로 건물 공간정보와 건축행정시스템(세움터)의 건축물대장 도면(배치도)을 통합하여 구축한 공간(토지)기반의 건물통합도면(이미지) 조회 |
| 인터페이스방식 | REST |
| 교환데이터표준 | XML |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th>비고</th></tr>
<tr><td rowspan="11">In</td><td>-</td><td>서비스ID</td><td>*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td>* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td>* 기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>GPKI인증서ID</td><td>옵션</td></tr>
<tr><td>-</td><td>소재지코드</td><td>*</td></tr>
<tr><td>-</td><td>대장구분</td><td>*</td></tr>
<tr><td>-</td><td>본번</td><td>*</td></tr>
<tr><td>-</td><td>부번</td><td></td></tr>
<tr><td>-</td><td>너비</td><td></td></tr>
<tr><td>-</td><td>높이</td><td></td></tr>
<tr><td>-</td><td>스케일</td><td></td></tr>
<tr><td>Out</td><td>BLDG_INTERG_IMG</td><td>건물통합도면이미지</td><td></td></tr>
</table>

□ 결과 XML

<RESPONSE>
<HEADER>
<CODE>0000</CODE>
<MESSAGE>SUCCESS</MESSAGE>
</HEADER>
<BODY>
<BLDG\_INTERG\_SET>
<BLDG\_INTERG\_IMG>
AQAAAPQAAAAAAAAAAAAAA...
</BLDG\_INTERG\_IMG>
</BLDG\_INTERG\_SET>
</BODY>
</RESPONSE>

15. SHAPE 다운로드

□ 서비스 개요

| 서비스명 | SHAPE 다운로드 |
| --- | --- |
| 서비스설명 | 연속도 및 연속도 기반 경계(읍면동, 리)를 SHAPE 파일로 제공<br>(매일 갱신하여 시군구 전체 정보를 제공함) |
| 인터페이스방식 | REST |
| 교환데이터표준 | FILE |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th>비고</th></tr>
<tr><td rowspan="5">In</td><td>-</td><td>서비스ID</td><td>*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td>* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td>기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>-</td><td>레이어코드</td><td>24. 레이어목록 조회 참조</td></tr>
<tr><td>-</td><td>파일타입</td><td>2: shp<br>3: dbf<br>4: shx</td></tr>
<tr><td>Out</td><td>파일</td><td>파일</td><td>파일</td></tr>
</table>

16. 토지기본정보 다운로드

□ 서비스 개요

| 서비스명 | 토지기본정보 다운로드 |
| --- | --- |
| 서비스설명 | 시군군 내 토지에 대한 최소 정보(지목, 면적, 소유구분)를 텍스트 파일로 제공 |
| 인터페이스방식 | REST |
| 교환데이터표준 | FILE<br>♂ 아스키코드값 (11) 을 구분자로 사용 |

□ 서비스 항목

<table>
<tr><th>구분</th><th>코드</th><th>코드명</th><th>비고</th></tr>
<tr><td rowspan="3">In</td><td>-</td><td>서비스ID</td><td>*</td></tr>
<tr><td>-</td><td>행정구역코드</td><td>* 행정구역코드는 필수</td></tr>
<tr><td>-</td><td>시스템코드</td><td>기관 시스템별 부여되는 고유코드</td></tr>
<tr><td>Out</td><td>파일</td><td>txt파일</td><td>txt파일</td></tr>
</table>

□ 결과 파일 구조

ADM\_SECT\_CD♂LAND\_LOC\_CD♂LEDG\_GBN♂BOBN♂BUBN♂JIMOK♂PAREA♂OWN\_GBN
27170♂10200♂1♂0399♂0036♂14♂47♂02
27170♂10200♂1♂0399♂0037♂14♂348♂04
.
.
.