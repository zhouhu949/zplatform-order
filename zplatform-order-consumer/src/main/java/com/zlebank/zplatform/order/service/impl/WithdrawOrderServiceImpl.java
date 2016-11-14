/* 
 * WithdrawOrderServiceImpl.java  
 * 
 * version TODO
 *
 * 2016年11月14日 
 * 
 * Copyright (c) 2016,zlebank.All rights reserved.
 * 
 */
package com.zlebank.zplatform.order.service.impl;

import java.math.BigDecimal;

import org.apache.commons.lang.math.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.zlebank.zplatform.acc.bean.TradeInfo;
import com.zlebank.zplatform.acc.service.entry.EntryEvent;
import com.zlebank.zplatform.commons.enums.BusinessCodeEnum;
import com.zlebank.zplatform.commons.utils.BeanCopyUtil;
import com.zlebank.zplatform.commons.utils.StringUtil;
import com.zlebank.zplatform.member.bean.QuickpayCustBean;
import com.zlebank.zplatform.member.pojo.PojoMerchDeta;
import com.zlebank.zplatform.order.bean.OrderBean;
import com.zlebank.zplatform.order.bean.WithdrawAccBean;
import com.zlebank.zplatform.order.bean.WithdrawBean;
import com.zlebank.zplatform.order.dao.TxncodeDefDAO;
import com.zlebank.zplatform.order.dao.TxnsLogDAO;
import com.zlebank.zplatform.order.dao.TxnsWithdrawDAO;
import com.zlebank.zplatform.order.dao.pojo.PojoTxncodeDef;
import com.zlebank.zplatform.order.dao.pojo.PojoTxnsLog;
import com.zlebank.zplatform.order.dao.pojo.PojoTxnsOrderinfo;
import com.zlebank.zplatform.order.dao.pojo.PojoTxnsWithdraw;
import com.zlebank.zplatform.order.exception.CommonException;
import com.zlebank.zplatform.order.exception.WithdrawOrderException;
import com.zlebank.zplatform.order.sequence.SerialNumberService;
import com.zlebank.zplatform.order.service.CommonOrderService;
import com.zlebank.zplatform.order.service.WithdrawOrderService;
import com.zlebank.zplatform.order.utils.Constant;
import com.zlebank.zplatform.rmi.member.ICoopInstiProductService;
import com.zlebank.zplatform.rmi.member.ICoopInstiService;
import com.zlebank.zplatform.rmi.member.IMemberBankCardService;
import com.zlebank.zplatform.rmi.member.IMerchService;
import com.zlebank.zplatform.trade.acc.bean.ResultBean;
import com.zlebank.zplatform.trade.acc.service.WithdrawAccountingService;
import com.zlebank.zplatform.trade.model.TxnsOrderinfoModel;
import com.zlebank.zplatform.trade.model.TxnsWithdrawModel;
import com.zlebank.zplatform.trade.utils.DateUtil;

/**
 * Class Description
 *
 * @author guojia
 * @version
 * @date 2016年11月14日 下午3:28:55
 * @since 
 */
@Service("withdrawOrderService")
public class WithdrawOrderServiceImpl implements WithdrawOrderService {
	
	@Autowired
	private IMemberBankCardService memberBankCardService;
	@Autowired
	private CommonOrderService commonOrderService;
	@Autowired
	private TxncodeDefDAO txncodeDefDAO;
	@Autowired
	private IMerchService merchService;
	@Autowired
	private ICoopInstiProductService coopInstiProductService;
	@Autowired
	private SerialNumberService serialNumberService;
	@Autowired
	private TxnsLogDAO txnsLogDAO;
	@Autowired
	private ICoopInstiService coopInstiService;
	@Autowired
	private TxnsWithdrawDAO txnsWithdrawDAO;
	@Autowired
	private WithdrawAccountingService withdrawAccountingService;
	/**
	 *
	 * @param withdrawBean
	 * @return
	 * @throws CommonException 
	 */
	@Override
	public String createIndividualWithdrawOrder(WithdrawBean withdrawBean) throws WithdrawOrderException, CommonException {
		WithdrawAccBean accBean = null;
		if (StringUtil.isNotEmpty(withdrawBean.getBindId())) {// 使用已绑定的卡进行提现
			QuickpayCustBean custCard = memberBankCardService.getMemberBankCardById(Long.valueOf(withdrawBean.getBindId()));
			if (custCard == null) {
				throw new WithdrawOrderException("GW13");
			}
			accBean = new WithdrawAccBean(custCard);
		} else {
			accBean = JSON.parseObject(withdrawBean.getCardData(),WithdrawAccBean.class);
		}
		if (accBean == null) {
			throw new WithdrawOrderException("GW14");
		}
		
		commonOrderService.verifyRepeatWithdrawOrder(withdrawBean);
		
		commonOrderService.verifyBusiness(BeanCopyUtil.copyBean(OrderBean.class, withdrawBean));
		
		//commonOrderService.verifyMerchantAndCoopInsti(withdrawBean.getMerId(), withdrawBean.getCoopInstiId());
		
		commonOrderService.checkBusiAcctOfWithdraw(withdrawBean.getMemberId(),withdrawBean.getAmount());
		
		try {
			return saveWithdrawOrder(withdrawBean,accBean);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new WithdrawOrderException("");
		}
	}

	/**
	 * @param withdrawBean
	 * @param accBean
	 * @throws WithdrawOrderException 
	 */
	public String saveWithdrawOrder(WithdrawBean withdrawBean,
			WithdrawAccBean accBean) throws WithdrawOrderException {
		// TODO Auto-generated method stub
		// 记录订单信息
		PojoTxnsOrderinfo orderinfo = null;
		PojoTxnsLog txnsLog = null;
		
		PojoTxncodeDef busiModel = txncodeDefDAO.getBusiCode(
				withdrawBean.getTxnType(), withdrawBean.getTxnSubType(),
				withdrawBean.getBizType());
		// member = memberService.get(withdrawBean.getCoopInstiId());
		txnsLog = new PojoTxnsLog();
		if (StringUtil.isNotEmpty(withdrawBean.getMerId())) {// 商户为空时，取商户的各个版本信息
			PojoMerchDeta member = merchService.getMerchBymemberId(withdrawBean
					.getMerId());
			txnsLog.setRiskver(member.getRiskVer());
			txnsLog.setSplitver(member.getSpiltVer());
			txnsLog.setFeever(member.getFeeVer());
			txnsLog.setPrdtver(member.getPrdtVer());
			txnsLog.setRoutver(member.getRoutVer());
			txnsLog.setAccsettledate(DateUtil.getSettleDate(Integer
					.valueOf(member.getSetlCycle().toString())));
		} else {
			txnsLog.setRiskver(coopInstiProductService.getDefaultVerInfo(
					withdrawBean.getCoopInstiId(), busiModel.getBusicode(),
					13));
			txnsLog.setSplitver(coopInstiProductService.getDefaultVerInfo(
					withdrawBean.getCoopInstiId(), busiModel.getBusicode(),
					12));
			txnsLog.setFeever(coopInstiProductService.getDefaultVerInfo(
					withdrawBean.getCoopInstiId(), busiModel.getBusicode(),
					11));
			txnsLog.setPrdtver(coopInstiProductService.getDefaultVerInfo(
					withdrawBean.getCoopInstiId(), busiModel.getBusicode(),
					10));
			txnsLog.setRoutver(coopInstiProductService.getDefaultVerInfo(
					withdrawBean.getCoopInstiId(), busiModel.getBusicode(),
					20));
			txnsLog.setAccsettledate(DateUtil.getSettleDate(1));
		}

		txnsLog.setTxndate(DateUtil.getCurrentDate());
		txnsLog.setTxntime(DateUtil.getCurrentTime());
		txnsLog.setBusicode(busiModel.getBusicode());
		txnsLog.setBusitype(busiModel.getBusitype());
		txnsLog.setTxnseqno(serialNumberService.generateTxnseqno());
		txnsLog.setAmount(Long.valueOf(withdrawBean.getAmount()));
		txnsLog.setAccordno(withdrawBean.getOrderId());
		txnsLog.setAccfirmerno(withdrawBean.getCoopInstiId());
		// 提现订单不记录商户号，记录在订单表中
		if ("3000".equals(txnsLog.getBusitype())) {
			txnsLog.setAccsecmerno("");
		} else {
			txnsLog.setAccsecmerno(withdrawBean.getMerId());
		}
		txnsLog.setAcccoopinstino(Constant.getInstance().getZlebank_coopinsti_code());
		txnsLog.setAccordcommitime(withdrawBean.getTxnTime());
		txnsLog.setTradestatflag("00000000");// 交易初始状态
		txnsLog.setAccmemberid(withdrawBean.getMemberId());
		//txnsLog.setTxnfee(txnsLogService.getTxnFee(txnsLog));
		txnsLogDAO.saveTxnsLog(txnsLog);

		

		
		orderinfo = new PojoTxnsOrderinfo();
		orderinfo.setId(Long.valueOf(RandomUtils.nextInt()));
		orderinfo.setOrderno(withdrawBean.getOrderId());// 商户提交的订单号
		orderinfo.setOrderamt(Long.valueOf(withdrawBean.getAmount()));
		orderinfo.setOrderfee(txnsLog.getTxnfee());
		orderinfo.setOrdercommitime(withdrawBean.getTxnTime());
		orderinfo.setRelatetradetxn(txnsLog.getTxnseqno());// 关联的交易流水表中的交易序列号
		orderinfo.setFirmemberno(withdrawBean.getCoopInstiId());
		orderinfo.setFirmembername(coopInstiService.getInstiByInstiCode(
				withdrawBean.getCoopInstiId()).getInstiName());

		//orderinfo.setBackurl(withdrawBean.getBackUrl());
		orderinfo.setTxntype(withdrawBean.getTxnType());
		orderinfo.setTxnsubtype(withdrawBean.getTxnSubType());
		orderinfo.setBiztype(withdrawBean.getBizType());
		orderinfo.setAccesstype(withdrawBean.getAccessType());
		orderinfo.setTn(serialNumberService.generateTN(orderinfo.getMemberid()));
		orderinfo.setMemberid(withdrawBean.getMemberId());
		orderinfo.setCurrencycode("156");
		orderinfo.setStatus("02");

	
	
		PojoTxnsWithdraw withdraw = new PojoTxnsWithdraw(withdrawBean,accBean);
		withdraw.setTexnseqno(txnsLog.getTxnseqno());
		withdraw.setFee(txnsLog.getTxnfee());
		txnsWithdrawDAO.saveTxnsWithdraw(withdraw);
		ResultBean resultBean = withdrawAccountingService.withdrawApply(txnsLog.getTxnseqno());
		if(resultBean.isResultBool()){
			return orderinfo.getTn();
		}else{
			throw new WithdrawOrderException("");
		}
		// 风控

		
		/*txnsLogService.tradeRiskControl(txnsLog.getTxnseqno(),
				txnsLog.getAccfirmerno(), txnsLog.getAccsecmerno(),
				txnsLog.getAccmemberid(), txnsLog.getBusicode(),
				txnsLog.getAmount() + "", "1", withdraw.getAcctno());
		txnsOrderinfoDAO.saveOrderInfo(orderinfo);*/
			
		/*// 提现账务处理
		TradeInfo tradeInfo = new TradeInfo();
		tradeInfo.setPayMemberId(txnsLog.getAccmemberid());
		tradeInfo.setPayToMemberId(txnsLog.getAccmemberid());
		tradeInfo.setAmount(new BigDecimal(txnsLog.getAmount()));
		tradeInfo
				.setCharge(new BigDecimal(
						txnsLog.getTxnfee() == null ? 0L : txnsLog
								.getTxnfee()));
		tradeInfo.setTxnseqno(txnsLog.getTxnseqno());
		tradeInfo.setBusiCode(BusinessCodeEnum.WITHDRAWALS
				.getBusiCode());
		tradeInfo.setAccess_coopInstCode(txnsLog.getAccfirmerno());
		tradeInfo.setCoopInstCode(txnsLog.getAcccoopinstino());
		// 记录分录流水
		accEntryService.accEntryProcess(tradeInfo,
				EntryEvent.AUDIT_APPLY);*/
			

		
		
	}

}
