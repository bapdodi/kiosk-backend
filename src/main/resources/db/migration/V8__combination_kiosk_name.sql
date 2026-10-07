-- 키오스크 화면에서만 쓰는 규격 표시 이름. null 이면 ERP 규격명(name)을 그대로 보여 준다.
ALTER TABLE public.combinations ADD COLUMN kiosk_name character varying(255);
